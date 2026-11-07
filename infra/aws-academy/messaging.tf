# SQS FIFO + DLQs: mesmos nomes do Kind (infra/phase4-kind/localstack/init.sh), maxReceiveCount=5.
locals {
  queues_with_dlq = toset(["billing-commands", "execution-commands", "os-saga-replies"])
}

resource "aws_sqs_queue" "dlq" {
  for_each = local.queues_with_dlq

  name                        = "${each.key}-dlq.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  visibility_timeout_seconds  = 60
  sqs_managed_sse_enabled     = true
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"
}

resource "aws_sqs_queue" "main" {
  for_each = local.queues_with_dlq

  name                        = "${each.key}.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  visibility_timeout_seconds  = 60
  sqs_managed_sse_enabled     = true
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = 5
  })
}

resource "aws_sqs_queue" "notifications" {
  name                        = "notifications.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  visibility_timeout_seconds  = 60
  sqs_managed_sse_enabled     = true
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"
}

# Sem aws_sqs_queue_policy: acesso apenas por identidade (LabRole); nenhum Principal "*".
