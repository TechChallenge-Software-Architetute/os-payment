output "input_queue_name" {
  description = "Input queue name the producer should send payment requests to."
  value       = aws_sqs_queue.input.name
}

output "input_queue_url" {
  description = "Input queue URL."
  value       = aws_sqs_queue.input.url
}

output "input_queue_arn" {
  description = "Input queue ARN for producer IAM policies."
  value       = aws_sqs_queue.input.arn
}

output "input_dlq_url" {
  description = "Input dead-letter queue URL."
  value       = aws_sqs_queue.input_dlq.url
}

output "output_queue_name" {
  description = "Queue name receiving payment actions."
  value       = aws_sqs_queue.output.name
}

output "output_queue_url" {
  description = "Queue URL receiving payment actions."
  value       = aws_sqs_queue.output.url
}

output "payment_orders_table_name" {
  description = "DynamoDB table name used by the worker, keyed by order with the action-updatedAt-index GSI."
  value       = aws_dynamodb_table.payment_orders.name
}

output "ecr_repository_url" {
  description = "ECR repository URL for the payment worker image."
  value       = aws_ecr_repository.app.repository_url
}

output "ecs_cluster_name" {
  description = "ECS cluster running the payment worker."
  value       = aws_ecs_cluster.app.name
}

output "ecs_service_name" {
  description = "ECS service running the payment worker."
  value       = aws_ecs_service.app.name
}
