output "input_queue_url" {
  value = aws_sqs_queue.input.url
}
output "output_queue_url" {
  value = aws_sqs_queue.output.url
}
output "payment_orders_table_name" {
  value = aws_dynamodb_table.payment_orders.name
}
output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}
output "ecs_cluster_name" {
  value = aws_ecs_cluster.app.name
}
output "ecs_service_name" {
  value = aws_ecs_service.app.name
}
