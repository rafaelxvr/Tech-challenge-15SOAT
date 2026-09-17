# Phase 3 release operations

These procedures are review checklists. They do not assert an active AWS account, cluster, queue, database, or New Relic tenant. Record the command output, source commit, artifact digest, environment, timestamp, and outcome in the R4 evidence record; keep secrets and personal data out of the record.

## Bootstrap and first writer

1. From the sibling K8S checkout, follow [protected bootstrap](../../../../oficina-k8s-infra/docs/bootstrap.md) during the authorized cloud window. Before any apply, run `pwsh -NoProfile -File ./tests/pipeline-contract.ps1`, `pwsh -NoProfile -File ./tests/release-readiness-contract.ps1`, and `terraform fmt -check -recursive` from that repository root.
2. Prove APP is ready before allowing writers: from APP root run `./mvnw.cmd -B verify`, `python scripts/check-doc-links.py docs README.md`, and `python scripts/verify-api-snapshots.py`.
3. The first writer is a controlled cutover, not a normal rolling rollout. If it is interrupted before migration completion, stop new writers, retain the failing migration/build evidence, and do not retry against a changed artifact. Resume only after the same reviewed artifact passes the migration/readiness gate described in [platform workloads](../../../../oficina-k8s-infra/docs/platform-workloads.md).

## Diagnosed failure and rollback

1. Identify the failing boundary using the safe correlation/request ID, environment label, build ID, and health/queue alarm. Do not copy JWTs, CPF, emails, connection strings, or provider responses into tickets.
2. For an application readiness or migration failure, keep the failed release evidence and stop promotion. Re-run staging only after a new reviewed commit; production requires the exact successful staging receipt. Do not use `terraform destroy` as rollback.
3. For a platform change, use the prior reviewed immutable release manifest/artifact and the release stop conditions in [deployment sequence](../../../../oficina-k8s-infra/docs/deployment-sequence.md). Confirm target registration and health before closing the incident.

## Key rotation and secret references

1. Rotate credentials in the approved external secret manager, never in Git, Postman exports, Terraform variables, logs, or evidence attachments.
2. Update only the approved environment-specific secret reference/role policy, run K8S local contracts, then deploy through the protected staging handoff. Confirm the workload reads the new reference without logging its value.
3. Retire the old version only after staging then production health, authentication, and notification checks have recorded evidence. If validation fails, restore the prior **reference** and investigate; do not reveal either value.

## Outbox, FIFO, and DLQ recovery

1. Diagnose whether the fault is pending/blocked APP outbox state, FIFO source age, Lambda processing, or DLQ depth. Preserve the event ID, order ID, sequence, safe error code, and correlation ID only.
2. Inspect APP `outbox_eventos` and its audited `outbox_recuperacoes` record. A reviewed operator records only `RETRY` or `SKIP` with a symbolic `operador_ref` and reason code; never alter event payload/contact data. The source model is [V7](../../../src/main/resources/db/migration/V7__criar_outbox_e_destinatario.sql).
3. For a DLQ message, inspect the safe event envelope, confirm the current `notificacao_destinatario_snapshot` and DynamoDB delivery-ledger state, then use the environment's reviewed replay procedure. Do not bulk-redrive or assume exactly-once delivery; an SES acceptance is not mailbox delivery.
4. Run the APP test gate before any retry procedure: `./mvnw.cmd -B verify`. Retain before/after queue depth, safe IDs, action code, and observed result in the evidence record.

## Evidence export and cleanup

1. Export only the K8S allowlisted release/output receipt through the reviewed [release-readiness process](../../../../oficina-k8s-infra/docs/release-readiness.md); include artifact/manifest SHA-256 and immutable object version, not state or secrets.
2. Attach the four repository commit IDs, CI run URLs/statuses, test outputs, diagrams, and recorded non-secret dashboard/log/tracing screenshots to the delivery PDF/video evidence.
3. Cleanup means remove local `.rendered`, `target`, and temporary evidence files after copying the approved non-secret record. Do not delete Terraform state, S3 locks, queues, databases, production resources, or cloud evidence during routine cleanup.

## Source activation and acceptance gate

APP/FUN live adapters are currently disabled; completing this checklist is not itself an activation command. Review [I7 prerequisites](../../i7-pipeline-contracts.md), FUN's single-owner state transfer and APP's missing cloud migration/rollout executor first. The K8S deployer source requires AWS CLI 2.36.42 for conditional lock deletion; build/review a new immutable image digest before execution. External permissions, current window and production authorization are separate evidence.

During first-writer cutover, drain old writers before V6 and use the reviewed migration artifact; on failure do not start new writers. Later compatible releases can roll forward only after migration success. Rollback is limited to a schema/security-compatible artifact: never restore the insecure writer or destructively reverse V5–V8. Measure interruption and recovery rather than claiming availability.

For customer-key rotation, install the new public kid in APP and authorizer first, then activate signing. Retain the old public key through the last old-key issuance plus its 900-second lifetime, configured skew and propagation interval. Staff HS256 secret rotation is separate and follows the approved staff re-login policy. See [FUN rotation details](../../../../oficina-functions/docs/token-trust.md).

Export owner-specific allowlisted receipts (APP schema/runtime refs, DB connection refs, FUN public/runtime refs and K8S platform refs), never full state. The [R3 evidence matrix](../evidence/requirements.md) links sources and pending acceptance. API snapshots remain pinned to their original revision; regenerate/review release exports only after verifying current contract compatibility and stripping credentials.