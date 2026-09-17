# Phase 3 implementation evidence audit

**Audit date:** 2026-09-17  
**Scope:** local source, tests, repository contracts and CI definitions in the four delivery repositories.

This audit records locally evidenced implementation work. It does not claim that AWS resources, protected environments, external publishing or end-to-end cloud acceptance exist.

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

## Explicitly not run

### R4 — cloud acceptance

All eight repository/environment records remain `NOT_RUN` in the [APP evidence manifest](manifest.json): APP staging/production, K8S staging/production, FUN staging/production and DB staging/production. The repository-specific status files remain explicit:

- [FUN R4 status](../../../../oficina-functions/docs/evidence/r4-local-status.json)
- [K8S R4 status](../../../../oficina-k8s-infra/docs/evidence/r4-local-status.json)
- [DB R4 status](../../../../oficina-db-infra/docs/evidence/r4-local-status.json)

The K8S staging control-plane observation records only the base API/platform handoff. It is not evidence of application, functions, database, production, protected-route, telemetry, failure-recovery or eight-run acceptance. Live AWS deployment, measured capacity/costs, SES/SNS/New Relic enrollment, branch protections and cleanup authorization remain outstanding.

### R5 — submission and publishing

The video, final PDF, external repository/reviewer access, video hosting and portal submission remain `NOT_RUN`. Prepared scripts and metadata may be reviewed locally, but no publication or submission is implied by this file.
