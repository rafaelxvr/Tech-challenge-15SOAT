# R3 requirement and evidence matrix

This is a source audit dated 2026-09-16, not a deployment receipt. Earlier evidence retains its original revision/status; `NOT_RUN` files are historical pending-evidence placeholders, not a live account inventory. No R4 status is advanced by this documentation change.

The [2026-09-18 APP LF activation checkpoint](app-staging-lf-activation-2026-09-18.md) records exact local hashes and the expired-SSO publication blocker without advancing R4.

The separate [redacted staging control-plane observation](staging-control-plane-observation.md) records supplied foundation/RDS status without advancing any of the eight deployment records or claiming APP/FUN deployment.

| Requirement | Reviewable source / decision | Staged acceptance gap |
| --- | --- | --- |
| DDD and components | [Components](../architecture/components.md), [ADR 001](../../adrs/001-modular-monolith.md), [RFC 001](../../rfcs/001-aws-profile.md) | Single-owner state inventory before FUN activation. |
| Separate customer/staff trust | [Authentication sequence](../architecture/authentication-sequence.md), [TokenTrustTest](../../../src/test/java/com/oficina/security/TokenTrustTest.java), [ADR 003](../../adrs/003-token-trust.md) | Live gateway 401/403, inactive actors, ownership and key rotation. |
| Relational truth and reporting | [ER model](../architecture/data-model.md), [RFC 002](../../rfcs/002-postgresql-model.md), [ADR 005](../../adrs/005-canonical-reporting.md) | Private role-denial/TLS tests, old-writer drain and schema cutover. |
| Asynchronous notifications | [Sequence](../architecture/order-opening-sequence.md), [OutboxTransactionTest](../../../src/test/java/com/oficina/adapter/out/outbox/OutboxTransactionTest.java), [ADR 004](../../adrs/004-outbox-delivery.md) | Real SQS/DLQ/SES failure windows; mailbox delivery differs from SES acceptance. |
| Health, telemetry, capacity | [RFC 005](../../rfcs/005-observability.md), [HealthGroupsTest](../../../src/test/java/com/oficina/config/HealthGroupsTest.java), [SnapshotExportTest](../../../src/test/java/com/oficina/adapter/out/observability/SnapshotExportTest.java), [ADR 002](../../adrs/002-environment-scaling.md), [K8S static observability contract](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/blob/f51d67ebc81235ed18239de8eed17d27a22a2f88/docs/evidence/monitoring-static-contract.md) | Health isolation, alert delivery, quotas, cold starts and measured resource budget. |
| Reproducible release | [I7 prerequisites](../../i7-pipeline-contracts.md), [pipeline contracts](../../../tests/pipeline-contract.ps1), [API/hash receipts](../api/contracts.md) | APP/FUN live adapters disabled; protections, eight release runs, current API export and access checks remain. |
| I6 migration and first writer | [Cutover contract](../../runbooks/first-writer-cutover.md), [mocked rollout tests](../../../tests/app-rollout-contract.ps1) | Source sequencing only: bootstrap Job wiring/private RDS grant proof, migration image, namespace RBAC/network setup, Kind V4→V8 and live interruption/compatible rollback evidence remain. |
| APP-owned role bootstrap | [Bootstrap adapter/runbook](../../runbooks/database-bootstrap.md), [PostgreSQL role tests](../../../src/test/java/com/oficina/bootstrap/DatabaseRolesTest.java), [v2 receipt](../../../contracts/bootstrap-receipt.v2.json) | Local real-PostgreSQL proof only; exact secret versions, RDS capability verification, reviewed bootstrap image/I6 wiring and v2 receipt consumption remain before staging. |

Current reviewed source references are indexed in the [implementation audit](implementation-audit.md); the dated baselines below retain their historical scope.

Repository matrices: [FUN](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/blob/dbceabcf3dd9d11597af98825c3ca54159cbbf4f/docs/evidence/requirements.md), [K8S](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/blob/f51d67ebc81235ed18239de8eed17d27a22a2f88/docs/evidence/requirements.md), [DB](https://github.com/rafaelxvr/Tech-challenge-15SOAT-db-infra/blob/33b7da9172c45d766d36e7561d59ee289053b02b/docs/evidence/requirements.md). Audited source baselines: APP `b4b1e8f`, FUN `471d072`, K8S `47fb380`, DB `ed7f5df`. These are local commits, not cloud receipts.

Run `python scripts/check-doc-links.py docs README.md` and `python scripts/verify-api-snapshots.py`. Python stdlib checks required files, local links, JSON and Mermaid declarations; it does not prove external access, diagram rendering, SQL execution or cloud readiness. R5 must verify published links/reviewer access and render the presentation separately.
