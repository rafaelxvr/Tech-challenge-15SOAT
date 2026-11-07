#!/bin/sh
# Read-only verification of the LocalStack contract. Exit code 1 on any mismatch.
set -eu
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
export AWS_EC2_METADATA_DISABLED=true AWS_ENDPOINT_URL=http://localhost:4566
fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS $1=$2"; else echo "FAIL $1 expected=$3 actual=$2"; fail=1; fi; }
attr() { awslocal sqs get-queue-attributes --queue-url "$(awslocal sqs get-queue-url --queue-name "$1" --query QueueUrl --output text)" \
  --attribute-names All --query "Attributes.$2" --output text; }

for base in billing-commands execution-commands os-saga-replies; do
  for q in "$base.fifo" "$base-dlq.fifo"; do
    check "$q.FifoQueue" "$(attr "$q" FifoQueue)" true
    check "$q.ContentBasedDeduplication" "$(attr "$q" ContentBasedDeduplication)" false
    check "$q.VisibilityTimeout" "$(attr "$q" VisibilityTimeout)" 60
    check "$q.FifoThroughputLimit" "$(attr "$q" FifoThroughputLimit)" perMessageGroupId
    check "$q.DeduplicationScope" "$(attr "$q" DeduplicationScope)" messageGroup
  done
  redrive="$(attr "$base.fifo" RedrivePolicy)"
  case "$redrive" in
    *"$base-dlq.fifo"*"\"maxReceiveCount\": \"5\""*|*"$base-dlq.fifo"*"\"maxReceiveCount\":\"5\""*|*"$base-dlq.fifo"*"\"maxReceiveCount\": 5"*|*"$base-dlq.fifo"*"\"maxReceiveCount\":5"*) echo "PASS $base.fifo.RedrivePolicy->dlq,max5" ;;
    *) echo "FAIL $base.fifo.RedrivePolicy=$redrive"; fail=1 ;;
  esac
done
check notifications.fifo.FifoQueue "$(attr notifications.fifo FifoQueue)" true

for t in oficina-phase4-execucoes oficina-phase4-execucao-outbox oficina-phase4-execucao-inbox; do
  check "table.$t.status" "$(awslocal dynamodb describe-table --table-name "$t" --query Table.TableStatus --output text)" ACTIVE
done
check "execucoes.gsi" "$(awslocal dynamodb describe-table --table-name oficina-phase4-execucoes --query 'Table.GlobalSecondaryIndexes[].IndexName' --output text | tr '\t' ',' | tr ',' '\n' | sort | tr '\n' ',')" "fila-index,os-index,"
check "outbox.gsi" "$(awslocal dynamodb describe-table --table-name oficina-phase4-execucao-outbox --query 'Table.GlobalSecondaryIndexes[].IndexName' --output text | tr '\t' ',' | tr ',' '\n' | sort | tr '\n' ',')" "estado-disponivel-index,os-sequencia-index,"
exit $fail
