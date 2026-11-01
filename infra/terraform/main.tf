# os-payment owns its inbound queue, its payments table, and the Lambda that
# joins the two. It never references another repo's Terraform state and never
# creates a resource inside another repo's stack, so this deploy runs in any
# order — including first, on an empty account.
#
# The event source mapping below targets *this* repository's queue, which is why
# it does not introduce a cross-repo dependency. A mapping onto someone else's
# queue would, and is not allowed here.

locals {
  queue_name    = "os-payment-${var.environment}-payment-requests"
  dlq_name      = "os-payment-${var.environment}-payment-requests-dlq"
  table_name    = "os-payment-${var.environment}-payments"
  function_name = "os-payment-${var.environment}-payment-handler"

  # Visibility must exceed the function timeout, or SQS redelivers a message that
  # is still being processed. Six times is the AWS-recommended ratio.
  queue_visibility_timeout_seconds = var.lambda_timeout_seconds * 6
}

# --- Inbound queue + DLQ -----------------------------------------------------

resource "aws_sqs_queue" "payment_requests_dlq" {
  name                      = local.dlq_name
  message_retention_seconds = var.dlq_message_retention_seconds
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "payment_requests" {
  name                       = local.queue_name
  visibility_timeout_seconds = local.queue_visibility_timeout_seconds
  message_retention_seconds  = var.queue_message_retention_seconds
  sqs_managed_sse_enabled    = true
  receive_wait_time_seconds  = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.payment_requests_dlq.arn
    maxReceiveCount     = var.queue_max_receive_count
  })
}

# Resource policy only for cross-account producers; see producer_role_arns.
data "aws_iam_policy_document" "payment_requests" {
  count = length(var.producer_role_arns) > 0 ? 1 : 0

  statement {
    sid       = "AllowNamedProducersToSend"
    effect    = "Allow"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.payment_requests.arn]

    principals {
      type        = "AWS"
      identifiers = var.producer_role_arns
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [aws_sqs_queue.payment_requests.arn]

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
  count = length(var.producer_role_arns) > 0 ? 1 : 0

  queue_url = aws_sqs_queue.payment_requests.id
  policy    = data.aws_iam_policy_document.payment_requests[0].json
}

# --- Payments table ----------------------------------------------------------

resource "aws_dynamodb_table" "payments" {
  name         = local.table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "paymentId"

  attribute {
    name = "paymentId"
    type = "S"
  }

  attribute {
    name = "orderId"
    type = "S"
  }

  # The saga looks payments up by order, not by payment id.
  global_secondary_index {
    name            = "orderId-index"
    hash_key        = "orderId"
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

# --- Lambda ------------------------------------------------------------------

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "payment_handler" {
  name               = "${local.function_name}-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "payment_handler_basic" {
  role       = aws_iam_role.payment_handler.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Least privilege: only this queue, only this table, and only the actions the
# handler actually performs. No VPC attachment — the function talks to SQS and
# DynamoDB over the AWS API and needs no private network access.
data "aws_iam_policy_document" "payment_handler" {
  statement {
    sid = "ConsumeOwnQueue"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.payment_requests.arn]
  }

  statement {
    sid       = "WritePayments"
    actions   = ["dynamodb:PutItem"]
    resources = [aws_dynamodb_table.payments.arn]
  }
}

resource "aws_iam_role_policy" "payment_handler" {
  name   = "payment-handler"
  role   = aws_iam_role.payment_handler.id
  policy = data.aws_iam_policy_document.payment_handler.json
}

# Created explicitly so retention is bounded; otherwise Lambda creates it with
# retention set to "never expire".
resource "aws_cloudwatch_log_group" "payment_handler" {
  name              = "/aws/lambda/${local.function_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "payment_handler" {
  function_name    = local.function_name
  role             = aws_iam_role.payment_handler.arn
  runtime          = var.lambda_runtime
  handler          = "com.os.payment.PaymentRequestHandler::handleRequest"
  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)
  memory_size      = var.lambda_memory_mb
  timeout          = var.lambda_timeout_seconds

  environment {
    variables = {
      PAYMENTS_TABLE = aws_dynamodb_table.payments.name
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.payment_handler_basic,
    aws_cloudwatch_log_group.payment_handler,
  ]
}

resource "aws_lambda_event_source_mapping" "payment_requests" {
  event_source_arn = aws_sqs_queue.payment_requests.arn
  function_name    = aws_lambda_function.payment_handler.arn
  batch_size       = var.lambda_batch_size

  # The handler returns per-message failures, so a single bad message does not
  # redrive the whole batch.
  function_response_types = ["ReportBatchItemFailures"]
}
