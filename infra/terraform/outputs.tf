# Producers (os-orchestrator) do NOT read these via terraform_remote_state. They
# rebuild the queue name from os-<service>-<env>-<purpose>, which is what keeps
# the deploys independent.

output "payment_requests_queue_name" {
  description = "Name of the inbound payment-requests queue."
  value       = aws_sqs_queue.payment_requests.name
}

output "payment_requests_queue_url" {
  description = "URL of the inbound payment-requests queue."
  value       = aws_sqs_queue.payment_requests.url
}

output "payment_requests_queue_arn" {
  description = "ARN of the inbound payment-requests queue. Producers grant sqs:SendMessage on this in their own IAM policy."
  value       = aws_sqs_queue.payment_requests.arn
}

output "payment_requests_dlq_url" {
  description = "URL of the payment-requests dead-letter queue."
  value       = aws_sqs_queue.payment_requests_dlq.url
}

output "payments_table_name" {
  description = "DynamoDB table holding payment records."
  value       = aws_dynamodb_table.payments.name
}

output "payments_table_arn" {
  description = "ARN of the payments table."
  value       = aws_dynamodb_table.payments.arn
}

output "payment_handler_function_name" {
  description = "Name of the payment handler Lambda."
  value       = aws_lambda_function.payment_handler.function_name
}
