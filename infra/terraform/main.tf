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

# --- Datadog -----------------------------------------------------------------

locals {
  # Datadog is on only when an API key is provided, the same switch the
  # os-management pipeline uses for its Agent. nonsensitive() is required because
  # count cannot depend on a sensitive value; it exposes only whether the key is
  # set, never the key itself.
  datadog_enabled = nonsensitive(var.datadog_api_key != "")

  # Same branch-to-env mapping as os-management, so the env filter in Datadog
  # shows every service of the saga together.
  datadog_env = lookup({ main = "production", develop = "homolog" }, var.environment, "developer")

  datadog_secret_arn = try(aws_secretsmanager_secret.datadog_api_key[0].arn, "")

  # JAVA_TOOL_OPTIONS loads the dd-java-agent baked into the image. Without it
  # (local runs, Datadog off) the jar stays inert. The tracer reaches the Agent on
  # localhost:8126, since containers of a Fargate task share the network.
  datadog_tracer_environment = [
    { name = "JAVA_TOOL_OPTIONS", value = "-javaagent:/app/dd-java-agent.jar" },
    { name = "DD_SERVICE", value = var.app_name },
    { name = "DD_ENV", value = local.datadog_env },
    { name = "DD_VERSION", value = var.image_tag },
    { name = "DD_LOGS_INJECTION", value = "true" },
  ]

  # Worker with the tracer on and its stdout routed to Datadog through FireLens.
  # The memory limit keeps the JVM (MaxRAMPercentage=75) from sizing its heap over
  # the sidecars' share of the task.
  datadog_worker_container = {
    name        = var.app_name
    image       = local.worker_image
    essential   = true
    memory      = local.task_memory - 384
    environment = concat(local.worker_environment, local.datadog_tracer_environment)
    dependsOn   = [{ containerName = "log-router", condition = "START" }]
    dockerLabels = {
      "com.datadoghq.tags.service" = var.app_name
      "com.datadoghq.tags.env"     = local.datadog_env
      "com.datadoghq.tags.version" = var.image_tag
    }
    logConfiguration = {
      logDriver = "awsfirelens"
      options = {
        Name           = "datadog"
        Host           = "http-intake.logs.${var.datadog_site}"
        TLS            = "on"
        provider       = "ecs"
        dd_service     = var.app_name
        dd_source      = "java"
        dd_message_key = "log"
        dd_tags        = "env:${local.datadog_env},version:${var.image_tag}"
      }
      secretOptions = [{ name = "apikey", valueFrom = local.datadog_secret_arn }]
    }
  }

  # Receives traces from the tracer and collects the task's container metrics.
  # Not essential: if it stops, the worker keeps processing payments.
  datadog_agent_container = {
    name              = "datadog-agent"
    image             = var.datadog_agent_image
    essential         = false
    memoryReservation = 256
    environment = [
      { name = "ECS_FARGATE", value = "true" },
      { name = "DD_SITE", value = var.datadog_site },
      { name = "DD_ENV", value = local.datadog_env },
      { name = "DD_APM_ENABLED", value = "true" },
    ]
    secrets = [{ name = "DD_API_KEY", valueFrom = local.datadog_secret_arn }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "datadog-agent"
      }
    }
  }

  # FireLens log router (Fluent Bit). Its own logs stay in CloudWatch, so a
  # delivery problem to Datadog can still be investigated.
  datadog_log_router_container = {
    name              = "log-router"
    image             = var.fluent_bit_image
    essential         = true
    memoryReservation = 64
    firelensConfiguration = {
      type    = "fluentbit"
      options = { "enable-ecs-log-metadata" = "true" }
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "log-router"
      }
    }
  }

  datadog_containers = [
    local.datadog_worker_container,
    local.datadog_agent_container,
    local.datadog_log_router_container,
  ]
}

# The API key lives in Secrets Manager rather than in the task definition: ECS
# injects it into the Agent (secrets) and into FireLens (secretOptions) at task
# start. Created only when a key is provided.
resource "aws_secretsmanager_secret" "datadog_api_key" {
  count = local.datadog_enabled ? 1 : 0

  name        = "${local.app_name}-datadog-api-key"
  description = "Datadog API key used by the os-payment ECS task (Agent and FireLens)."

  # Immediate deletion, so a destroy followed by a new deploy can reuse the name.
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "datadog_api_key" {
  count = local.datadog_enabled ? 1 : 0

  secret_id     = aws_secretsmanager_secret.datadog_api_key[0].id
  secret_string = var.datadog_api_key
}

# ECS resolves `secrets` and `secretOptions` with the execution role, not the task
# role, so the read permission goes there, scoped to this one secret.
data "aws_iam_policy_document" "execution_datadog" {
  count = local.datadog_enabled ? 1 : 0

  statement {
    sid       = "ReadDatadogApiKey"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.datadog_api_key[0].arn]
  }
}

resource "aws_iam_role_policy" "execution_datadog" {
  count  = local.datadog_enabled ? 1 : 0
  name   = "${local.app_name}-datadog-secret"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_datadog[0].json
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

locals {
  worker_image = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"

  # The Datadog Agent and the log router need room next to the JVM; the smallest
  # Fargate size that fits the three containers is 0.5 vCPU / 1 GB.
  task_cpu    = local.datadog_enabled ? max(var.task_cpu, 512) : var.task_cpu
  task_memory = local.datadog_enabled ? max(var.task_memory, 1024) : var.task_memory

  # Shared by both container definitions (with and without Datadog).
  worker_environment = [
    { name = "AWS_REGION", value = var.region },
    { name = "SQS_INPUT_QUEUE", value = aws_sqs_queue.input.name },
    { name = "SQS_OUTPUT_QUEUE", value = aws_sqs_queue.output.name },
    { name = "DYNAMODB_PAYMENT_ORDERS_TABLE", value = aws_dynamodb_table.payment_orders.name },
    { name = "PAYMENT_POLLING_FIXED_DELAY_MS", value = tostring(var.payment_polling_fixed_delay_ms) },
    { name = "PAYMENT_POLLING_BATCH_SIZE", value = tostring(var.payment_polling_batch_size) },
    { name = "MERCADO_PAGO_API_URL", value = var.mercado_pago_api_url },
  ]

  # Worker alone, as before Datadog.
  worker_container = {
    name        = var.app_name
    image       = local.worker_image
    essential   = true
    environment = local.worker_environment
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "worker"
      }
    }
  }
}

resource "aws_ecs_task_definition" "app" {
  family                   = local.app_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(local.task_cpu)
  memory                   = tostring(local.task_memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  # Each branch is encoded to JSON on its own, so the two never need a common
  # type: the worker alone, or the worker plus the Datadog sidecars.
  container_definitions = local.datadog_enabled ? jsonencode(local.datadog_containers) : jsonencode([local.worker_container])
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

  # With Datadog off these two lists have no instances and add no dependency.
  depends_on = [
    aws_iam_role_policy_attachment.execution,
    aws_iam_role_policy.task,
    aws_iam_role_policy.execution_datadog,
    aws_secretsmanager_secret_version.datadog_api_key,
  ]

  # The workflow registers a task-definition revision with the Mercado Pago
  # token from GitHub Secrets. Do not have Terraform revert that revision.
  lifecycle {
    ignore_changes = [task_definition]
  }
}
