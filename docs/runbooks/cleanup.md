# Phase 3 cleanup proposal

Status: **proposal only**. Do not execute cleanup, schedule it, or mark an endpoint offline until a separately authorized cloud run has produced the real resource inventory and evidence export.

1. Export the non-secret acceptance receipt: source/artifact/plan digests, immutable object versions, CI/build IDs, result, timestamp, and approved dashboard/log/trace screenshots. Preserve the required one-day log evidence before retention expires.
2. Inventory exact resource IDs and retention requirements for EKS/node groups, RDS/snapshots, Lambda/layers, API Gateway, SQS/DLQ, DynamoDB, CloudWatch logs/alarms/SNS, Secrets Manager references, IAM roles, S3 artifacts/state, and New Relic dashboards/alerts. Compare the inventory with reviewed Terraform state; do not delete locks or state files manually.
3. Estimate retained and deletion costs, obtain explicit destructive-action confirmation, then use the reviewed owner-specific teardown plan. Preserve database snapshots and evidence exports according to the approved retention decision.
4. After authorized deletion is verified, disable synthetic pings, mute operational alerts through the approved provider workflow, and record endpoints as offline. A failed or skipped cleanup stays `FAIL`/`NOT_RUN` in the evidence manifest.
