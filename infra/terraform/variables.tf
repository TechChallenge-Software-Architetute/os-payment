variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "aws_account_id" {
  description = "Expected AWS account ID. Empty disables the account guardrail."
  type        = string
  default     = ""
}

variable "environment" {
  description = "Environment name, normally develop or main."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,20}$", var.environment))
    error_message = "environment must be lowercase alphanumeric with hyphens, starting with a letter."
  }
}

variable "app_name" {
  description = "Base name used for the application and ECR repository."
  type        = string
  default     = "os-payment"
}

variable "image_tag" {
  description = "Immutable container image tag to run. The pipeline sets this to the commit SHA."
  type        = string
  default     = "latest"
}

variable "desired_count" {
  description = "Number of payment worker tasks to run."
  type        = number
  default     = 1
}

variable "task_cpu" {
  description = "Fargate task CPU units."
  type        = number
  default     = 256
}

variable "task_memory" {
  description = "Fargate task memory in MiB."
  type        = number
  default     = 512
}

variable "queue_message_retention_seconds" {
  description = "How long messages remain in the input queue."
  type        = number
  default     = 345600
}

variable "queue_visibility_timeout_seconds" {
  description = "SQS visibility timeout for the input queue."
  type        = number
  default     = 180

  validation {
    condition     = var.queue_visibility_timeout_seconds >= 180
    error_message = "queue_visibility_timeout_seconds must be at least 180 seconds to cover worker retries."
  }
}

variable "dlq_message_retention_seconds" {
  description = "How long messages remain in the input dead-letter queue."
  type        = number
  default     = 1209600
}

variable "queue_max_receive_count" {
  description = "Deliveries before an input message is moved to the DLQ."
  type        = number
  default     = 5
}

variable "producer_role_arns" {
  description = "Specific cross-account producer principals allowed to send to the input queue."
  type        = list(string)
  default     = []

  validation {
    condition     = !contains(var.producer_role_arns, "*")
    error_message = "producer_role_arns must name specific principals; wildcard principals are not allowed."
  }
}

variable "enable_point_in_time_recovery" {
  description = "Enable point-in-time recovery for the payment orders table."
  type        = bool
  default     = true
}

variable "payment_polling_fixed_delay_ms" {
  description = "Delay between polling cycles, in milliseconds."
  type        = number
  default     = 30000
}

variable "payment_polling_batch_size" {
  description = "Maximum pending payments checked during one polling cycle."
  type        = number
  default     = 50
}

variable "mercado_pago_api_url" {
  description = "Mercado Pago API base URL. The access token is injected by GitHub Actions and is not stored in Terraform state."
  type        = string
  default     = "https://api.mercadopago.com"
}

variable "log_retention_days" {
  description = "CloudWatch log retention for the worker."
  type        = number
  default     = 30
}
