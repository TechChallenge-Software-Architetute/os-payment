variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "aws_account_id" {
  description = "Expected AWS account ID. Guards the provider against deploying to the wrong account. Empty = no restriction (local validation)."
  type        = string
  default     = ""
}

variable "environment" {
  description = "Environment name. By convention this is the branch name (develop/main)."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,20}$", var.environment))
    error_message = "environment must be lowercase alphanumeric with hyphens, starting with a letter."
  }
}

# --- Inbound queue -----------------------------------------------------------

variable "queue_message_retention_seconds" {
  description = "How long an unconsumed message stays on the main queue (seconds)."
  type        = number
  default     = 345600 # 4 days
}

variable "dlq_message_retention_seconds" {
  description = "How long a message stays on the DLQ (seconds)."
  type        = number
  default     = 1209600 # 14 days
}

variable "queue_max_receive_count" {
  description = "Deliveries attempted before a message is redriven to the DLQ."
  type        = number
  default     = 5
}

variable "producer_role_arns" {
  description = <<-EOT
    IAM role/user ARNs allowed to sqs:SendMessage to the payment-requests queue —
    in practice the os-orchestrator pod role.

    Empty (the default) creates no resource policy: a same-account producer is
    authorised by its own IAM policy, which keeps this deploy independent of the
    os-orchestrator deploy. Populate it only for cross-account producers. Never
    widen this to "*".
  EOT
  type        = list(string)
  default     = []

  validation {
    condition     = !contains(var.producer_role_arns, "*")
    error_message = "producer_role_arns must name specific principals; a wildcard principal would make the queue writable by any AWS account."
  }
}

# --- Payments table ----------------------------------------------------------

variable "enable_point_in_time_recovery" {
  description = "Enable DynamoDB point-in-time recovery on the payments table."
  type        = bool
  default     = true
}

# --- Lambda ------------------------------------------------------------------

variable "lambda_jar_path" {
  description = "Path to the shaded jar built by `./mvnw package`, relative to this directory."
  type        = string
  default     = "../../target/os-payment.jar"
}

variable "lambda_runtime" {
  description = "Lambda Java runtime."
  type        = string
  default     = "java21"
}

variable "lambda_memory_mb" {
  description = "Lambda memory, which also scales its CPU allocation."
  type        = number
  default     = 512
}

variable "lambda_timeout_seconds" {
  description = "Lambda timeout. Must be shorter than the queue's visibility timeout."
  type        = number
  default     = 30
}

variable "lambda_batch_size" {
  description = "Messages delivered to one Lambda invocation."
  type        = number
  default     = 10
}

variable "log_retention_days" {
  description = "CloudWatch log retention for the function."
  type        = number
  default     = 14
}
