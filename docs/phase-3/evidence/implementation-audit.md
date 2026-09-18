# Phase 3 implementation evidence audit

**Audit date:** 2026-09-18  
**Scope:** local source, tests, repository contracts and CI definitions in the four delivery repositories.

This audit records locally evidenced implementation work and the staging receipts that have since been captured. It does not claim production, application end-to-end acceptance, protected-environment completeness, external publishing or portal submission.

## Repository and CI evidence

| Repository | Reviewed revision | Local CI/contract evidence |
| --- | --- | --- |
| APP | `d181e6b9c248e9f255199eed64f97e3d902b0c55` | Java 17 Maven wrapper/toolchain files, focused application tests, `mvnw.cmd verify` work, and `.github/workflows/ci-cd.yml` |
| FUN | `af73d643a49b73eec54a854311519f02fbecf4f2` | Maven/runtime tests, `tests/pipeline-contract.ps1`, and `.github/workflows/ci.yml` |
| K8S | `105f7f31e5df0b1ce781aae442c73d266009efc8` | Terraform/PowerShell contract suite, pinned Terraform/Helm setup, provider initialization and `.github/workflows/ci-cd.yml` |
| DB | `e1047320ab76347c0400f6e8b6781043d9f46868` | PostgreSQL/root Terraform tests, `tests/pipeline-contract.ps1`, and `.github/workflows/ci-cd.yml` |

The canonical local evidence index is [the requirements matrix](requirements.md). The APP baseline is recorded in [baseline.md](baseline.md). The K8S staging control-plane observation is deliberately separate from acceptance evidence in [staging-control-plane-observation.md](staging-control-plane-observation.md).

## Locally evidenced task families

| Family | Evidence recorded locally |
| --- | --- |
| B1/B2 | Reproducible Java 17 wrapper/toolchain files, versioned `contracts/phase3-v1` and `contracts/phase3-v2`, APP/FUN contract tests, and repository READMEs/boundaries. |
| A1-A7 | Customer identity/versioning, canonical history/concurrency, token trust, owned routes, transactional outbox/FIFO publication and SQL reporting are covered by the APP entity, repository, security, controller, outbox, SQS and reporting tests. |
| F1-F5 | CPF/OTP use cases, DynamoDB conditional request shapes, restricted JDBC lookup, token/route policy, Lambda HTTP/SES adapters, notification leasing/deduplication and telemetry privacy are covered by the FUN test suite. |
| I1-I7 | K8S bootstrap/network/cluster/executor/platform/gateway/pipeline contracts and DB PostgreSQL/root/pipeline contracts are present and locally exercised. The APP rollout, migration bootstrap and immutable artifact contracts are also present. |
| R1-R3 | APP privacy/health/trace/snapshot tests, FUN telemetry tests, K8S New Relic chart/Terraform accounting, architecture diagrams, RFCs, ADRs, runbooks and repository READMEs are present. |

Representative local checks include `Phase3ContractTest`, `ClienteIdentityTest`, `ConcorrenciaAgregadosTest`, `TokenTrustTest`, `CustomerOrderSecurityTest`, `OutboxTransactionTest`, `OutboxPublisherTest`, `RelatorioPeriodoTest`, `CpfAuthenticationTest`, `DynamoDesafioStoreTest`, `TokenAndRoutePolicyTest`, `NotificarStatusTest`, K8S `pipeline-contract.ps1`, `executor-bootstrap-harness.ps1`, `newrelic-chart-tests.ps1`, and DB `pipeline-contract.ps1` plus PostgreSQL Terraform tests.

## Staging receipts now available

The central matrix remains fail-closed until every required repository/environment record is complete, but these reviewed staging receipts are now durable:

- [K8S platform acceptance](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/blob/5ecf7234bc4fc741e6cf7272f53622099cc74bf5/docs/evidence/staging-platform-acceptance-2026-09-17.md) — platform Terraform executor `SUCCEEDED`; application/runtime behavior remains outside this receipt.
- [DB platform acceptance](https://github.com/rafaelxvr/Tech-challenge-15SOAT-db-infra/blob/33b7da9172c45d766d36e7561d59ee289053b02b/docs/evidence/staging-database-platform-acceptance-2026-09-17.md) — private encrypted PostgreSQL `SUCCEEDED`; SQL grants, migrations and APP integration remain outside this receipt.
- [FUN staging runtime receipt](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/blob/329c4e95ee6ac63a40b6abd2234c4522992987ff/docs/evidence/staging-runtime-handoff-contract.md) — workflow `35304367076`, verification and private CodeBuild deployment succeeded with immutable source/artifact/manifest hashes; production was skipped.

These records do not change `docs/phase-3/evidence/manifest.json`, whose eight-record verification still correctly refuses an incomplete acceptance matrix.

## Explicitly not run

### R4 — cloud acceptance

The [APP evidence manifest](manifest.json) remains `NOT_RUN` because APP staging runtime, all production runs, and the required failure/recovery matrix are still incomplete. The repository-specific status files remain explicit:

- [FUN R4 status](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/blob/329c4e95ee6ac63a40b6abd2234c4522992987ff/docs/evidence/r4-local-status.json)
- [K8S R4 status](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/blob/5ecf7234bc4fc741e6cf7272f53622099cc74bf5/docs/evidence/r4-local-status.json)
- [DB R4 status](https://github.com/rafaelxvr/Tech-challenge-15SOAT-db-infra/blob/33b7da9172c45d766d36e7561d59ee289053b02b/docs/evidence/r4-local-status.json)

The K8S staging control-plane observation records only the base API/platform handoff. It is not evidence of application, functions, database, production, protected-route, telemetry, failure-recovery or eight-run acceptance. Live AWS deployment, measured capacity/costs, SES/SNS/New Relic enrollment, branch protections and cleanup authorization remain outstanding.

### R5 — submission and publishing

The video, final PDF, external repository/reviewer access, video hosting and portal submission remain `NOT_RUN`. Prepared scripts and metadata may be reviewed locally, but no publication or submission is implied by this file.
