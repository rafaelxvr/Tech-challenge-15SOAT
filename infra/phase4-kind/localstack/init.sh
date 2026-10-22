#!/bin/sh
# Idempotent LocalStack bootstrap (Plan 0 §8 contract). Runs INSIDE the LocalStack pod via
# `kubectl exec -i ... sh -s`, so it only ever talks to localhost:4566. Dummy credentials only.
set -eu
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
export AWS_EC2_METADATA_DISABLED=true AWS_ENDPOINT_URL=http://localhost:4566
ACCOUNT=000000000000

queue_exists() { awslocal sqs get-queue-url --queue-name "$1" >/dev/null 2>&1; }

create_fifo() { # name, dlq-name-or-empty
  name="$1"; dlq="${2:-}"
  if queue_exists "$name"; then echo "queue $name: exists"; return 0; fi
  attrs=/tmp/attrs.json
  redrive=""
  if [ -n "$dlq" ]; then
    redrive=",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"arn:aws:sqs:us-east-1:$ACCOUNT:$dlq\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\""
  fi
  printf '{"FifoQueue":"true","ContentBasedDeduplication":"false","VisibilityTimeout":"60"%s}' "$redrive" > "$attrs"
  awslocal sqs create-queue --queue-name "$name" --attributes "file://$attrs" >/dev/null
  # High-throughput FIFO settings must be set on the created queue.
  awslocal sqs set-queue-attributes --queue-url "$(awslocal sqs get-queue-url --queue-name "$name" --query QueueUrl --output text)" \
    --attributes '{"FifoThroughputLimit":"perMessageGroupId","DeduplicationScope":"messageGroup"}'
  echo "queue $name: created"
}

for base in billing-commands execution-commands os-saga-replies; do
  create_fifo "$base-dlq.fifo" ""
  create_fifo "$base.fifo" "$base-dlq.fifo"
done
create_fifo notifications.fifo ""

table_exists() { awslocal dynamodb describe-table --table-name "$1" >/dev/null 2>&1; }

if table_exists oficina-phase4-execucoes; then echo "table oficina-phase4-execucoes: exists"; else
  awslocal dynamodb create-table --table-name oficina-phase4-execucoes --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=execucaoId,AttributeType=S AttributeName=filaChave,AttributeType=S \
      AttributeName=osId,AttributeType=S AttributeName=enfileiradoEm,AttributeType=N \
    --key-schema AttributeName=execucaoId,KeyType=HASH \
    --global-secondary-indexes \
      'IndexName=fila-index,KeySchema=[{AttributeName=filaChave,KeyType=HASH},{AttributeName=enfileiradoEm,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
      'IndexName=os-index,KeySchema=[{AttributeName=osId,KeyType=HASH},{AttributeName=enfileiradoEm,KeyType=RANGE}],Projection={ProjectionType=ALL}' >/dev/null
  echo "table oficina-phase4-execucoes: created"
fi
if table_exists oficina-phase4-execucao-outbox; then echo "table oficina-phase4-execucao-outbox: exists"; else
  awslocal dynamodb create-table --table-name oficina-phase4-execucao-outbox --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=messageId,AttributeType=S AttributeName=estado,AttributeType=S \
      AttributeName=disponivelEm,AttributeType=N AttributeName=osId,AttributeType=S AttributeName=sequencia,AttributeType=N \
    --key-schema AttributeName=messageId,KeyType=HASH \
    --global-secondary-indexes \
      'IndexName=estado-disponivel-index,KeySchema=[{AttributeName=estado,KeyType=HASH},{AttributeName=disponivelEm,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
      'IndexName=os-sequencia-index,KeySchema=[{AttributeName=osId,KeyType=HASH},{AttributeName=sequencia,KeyType=RANGE}],Projection={ProjectionType=ALL}' >/dev/null
  echo "table oficina-phase4-execucao-outbox: created"
fi
if table_exists oficina-phase4-execucao-inbox; then echo "table oficina-phase4-execucao-inbox: exists"; else
  awslocal dynamodb create-table --table-name oficina-phase4-execucao-inbox --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=messageId,AttributeType=S \
    --key-schema AttributeName=messageId,KeyType=HASH >/dev/null
  echo "table oficina-phase4-execucao-inbox: created"
fi
echo "init: ok"
exit 0
