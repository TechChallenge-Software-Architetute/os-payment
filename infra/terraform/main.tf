data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

locals {
  app_name          = "${var.app_name}-${var.environment}"
  input_queue_name  = "${local.app_name}-payment-requests"
  dlq_name          = "${local.input_queue_name}-dlq"
  output_queue_name = "${local.app_name}-payment-status"
  table_name        = "${local.app_name}-payment-orders"
  log_group_name    = "/ecs/${local.app_name}"
}

# Keep the queues already applied by the previous Terraform version instead of
# replacing them when their resource labels are aligned with the worker code.
moved {
  from = aws_sqs_queue.payment_requests
  to   = aws_sqs_queue.input
}

moved {
  from = aws_sqs_queue.payment_requests_dlq
  to   = aws_sqs_queue.input_dlq
}

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# --- SQS ---------------------------------------------------------------------

resource "aws_sqs_queue" "input_dlq" {
  name                      = local.dlq_name
  message_retention_seconds = var.dlq_message_retention_seconds
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "input" {
  name                       = local.input_queue_name
  visibility_timeout_seconds = var.queue_visibility_timeout_seconds
  message_retention_seconds  = var.queue_message_retention_seconds
  receive_wait_time_seconds  = 20
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.input_dlq.arn
    maxReceiveCount     = var.queue_max_receive_count
  })
}

resource "aws_sqs_queue" "output" {
  name                    = local.output_queue_name
  sqs_managed_sse_enabled = true
}

data "aws_iam_policy_document" "input_queue" {
  count = length(var.producer_role_arns) > 0 ? 1 : 0

  statement {
    sid       = "AllowNamedProducersToSend"
    effect    = "Allow"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.input.arn]
    principals {
      type        = "AWS"
      identifiers = var.producer_role_arns
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [aws_sqs_queue.input.arn]
    principals {
      type        = "AWS"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_sqs_queue_policy" "payment_requests" {
  count     = length(var.producer_role_arns) > 0 ? 1 : 0
  queue_url = aws_sqs_queue.input.id
  policy    = data.aws_iam_policy_document.input_queue[0].json
}

# --- DynamoDB ---------------------------------------------------------------

resource "aws_dynamodb_table" "payment_orders" {
  name         = local.table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "order"

  attribute {
    name = "order"
    type = "S"
  }
  attribute {
    name = "action"
    type = "S"
  }
  attribute {
    name = "updatedAt"
    type = "S"
  }

  global_secondary_index {
    name            = "action-updatedAt-index"
    hash_key        = "action"
    range_key       = "updatedAt"
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = var.enable_point_in_time_recovery
  }
  server_side_encryption {
    enabled = true
  }

  lifecycle {
    prevent_destroy = true
  }
}

# --- ECR and ECS Fargate worker ---------------------------------------------

resource "aws_ecr_repository" "app" {
  name                 = local.app_name
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecs_cluster" "app" {
  name = local.app_name
}

resource "aws_cloudwatch_log_group" "app" {
  name              = local.log_group_name
  retention_in_days = var.log_retention_days
}

resource "aws_iam_role" "execution" {
  name               = "${local.app_name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role" "task" {
  name               = "${local.app_name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "task" {
  statement {
    sid = "ConsumeInputQueue"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:GetQueueUrl",
      "sqs:ChangeMessageVisibility",
    ]
    resources = [aws_sqs_queue.input.arn]
  }

  statement {
    sid       = "PublishOutputQueue"
    actions   = ["sqs:SendMessage", "sqs:GetQueueUrl"]
    resources = [aws_sqs_queue.output.arn]
  }

  statement {
    sid = "ReadAndUpdatePaymentOrders"
    actions = [
      "dynamodb:DescribeTable",
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:Query",
      "dynamodb:UpdateItem",
    ]
    resources = [
      aws_dynamodb_table.payment_orders.arn,
      "${aws_dynamodb_table.payment_orders.arn}/index/*",
    ]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "${local.app_name}-worker"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

resource "aws_security_group" "tasks" {
  name        = "${local.app_name}-tasks"
  description = "Outbound access for the os-payment worker"
  vpc_id      = data.aws_vpc.default.id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_ecs_task_definition" "app" {
  family                   = local.app_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.task_cpu)
  memory                   = tostring(var.task_memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([{
    name      = var.app_name
    image     = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"
    essential = true
    environment = [
      { name = "AWS_REGION", value = var.region },
      { name = "SQS_INPUT_QUEUE", value = aws_sqs_queue.input.name },
      { name = "SQS_OUTPUT_QUEUE", value = aws_sqs_queue.output.name },
      { name = "DYNAMODB_PAYMENT_ORDERS_TABLE", value = aws_dynamodb_table.payment_orders.name },
      { name = "PAYMENT_POLLING_FIXED_DELAY_MS", value = tostring(var.payment_polling_fixed_delay_ms) },
      { name = "PAYMENT_POLLING_BATCH_SIZE", value = tostring(var.payment_polling_batch_size) },
      { name = "MERCADO_PAGO_API_URL", value = var.mercado_pago_api_url },
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "worker"
      }
    }
  }])
}

resource "aws_ecs_service" "app" {
  name            = local.app_name
  cluster         = aws_ecs_cluster.app.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = data.aws_subnets.default.ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  depends_on = [
    aws_iam_role_policy_attachment.execution,
    aws_iam_role_policy.task,
  ]

  # The workflow registers a task-definition revision with the Mercado Pago
  # token from GitHub Secrets. Do not have Terraform revert that revision.
  lifecycle {
    ignore_changes = [task_definition]
  }
}
