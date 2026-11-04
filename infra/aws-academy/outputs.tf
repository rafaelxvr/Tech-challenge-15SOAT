output "compute_mode" {
  value = var.compute_mode
}

output "cluster_name" {
  description = "Nome do cluster EKS (vazio no modo k3s)."
  value       = var.compute_mode == "eks" ? aws_eks_cluster.this[0].name : ""
}

output "k3s_public_ip" {
  description = "IP publico da EC2 k3s (vazio no modo eks). Tratar como dado a mascarar em midia."
  value       = var.compute_mode == "k3s" ? aws_instance.k3s[0].public_ip : ""
  sensitive   = true
}

output "database_endpoints" {
  description = "Endpoints dos RDS por servico (mascarar em midia)."
  value       = { for k, v in aws_db_instance.this : k => v.address }
  sensitive   = true
}

output "database_master_passwords" {
  description = "Senhas master dos RDS. Usar so para migration, por stdin; nunca copiar para issue/PR/log."
  value       = { for k, v in random_password.db_master : k => v.result }
  sensitive   = true
}

output "sqs_queue_urls" {
  value = merge(
    { for k, v in aws_sqs_queue.main : k => v.url },
    { for k, v in aws_sqs_queue.dlq : "${k}-dlq" => v.url },
    { notifications = aws_sqs_queue.notifications.url },
  )
  sensitive = true
}

output "dynamodb_tables" {
  value = [
    aws_dynamodb_table.execucoes.name,
    aws_dynamodb_table.outbox.name,
    aws_dynamodb_table.inbox.name,
  ]
}
