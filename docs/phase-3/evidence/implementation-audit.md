# Phase 3 implementation evidence audit

**Audit date:** 2026-09-17  
**Scope:** local source, tests, repository contracts and CI definitions in the four delivery repositories.

This audit records locally evidenced implementation work. It does not claim that AWS resources, protected environments, external publishing or end-to-end cloud acceptance exist.

## Verification refresh — 2026-09-18

The current develop revisions were rechecked from clean detached worktrees after the APP checklist, FUN contract-byte, and K8S contract-test fixes landed:

| Repository | Current reviewed revision | Verification evidence |
| --- | --- | --- |
| APP | `9725629b6666b60169447265f1f55e0cf85983d9` | Pipeline contracts, documentation links, and the pinned submission suite passed. The submission suite completed 27 checks, including deterministic `NOT_READY / FIXTURE_ONLY` PDF rendering with `reportlab==4.4.9`, `pypdf==6.10.0`, and `pypdfium2==5.13.0`. |
| K8S | `7991a322b7bb7f1ee70e11404f817e11e318d9b2` | PR [#25](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/pull/25) passed CI run [35313825314](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/actions/runs/35313825314). The portable route hash and foundation-addons chart-pin contracts pass; deployment jobs were skipped. |
| FUN | `c2b2cdcf1d4101c268dbdc273549eae84fa0870d` | Cloud-window, lock, release-guard, secret-preparation, JWT-sync and workflow-context contracts passed locally; the merged push workflow also reached its deployment guard in CI run [35315358633](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/actions/runs/35315358633). |
| DB | `33b7da9172c45d766d36e7561d59ee289053b02b` | Bootstrap, cloud-window, lock, launcher, outputs, pipeline, source-package and workflow-context contracts passed locally. |

The local checks made no cloud calls. The merged FUN push workflow run [35315358633](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/actions/runs/35315358633) and K8S push workflow run [35313930756](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/actions/runs/35313930756) assumed their configured OIDC launcher roles, then stopped at the closed cloud-window guard before making S3, CodeBuild or deployment API calls; neither attempted a deployment. These runs provide workflow-guard evidence, while the local checks remain source and contract evidence only; no Terraform apply, Kubernetes API, external URL, video-hosting or student-portal operation was performed.

## APP offline release handoff receipt

Merged PR [#11](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/11) landed as reviewed revision `6edc75898a4624e2969e0cd87238717865643b14`. The deterministic offline handoff packages that source revision, verifies the archive and canonical release manifest, renders the migration/rollout outputs, and writes a receipt with `status: INPUTS_VALIDATED_DEPLOYMENT_DISABLED`, `success: false`, and `deploymentAttempted: false`. The exact contract path is `tests/pipeline-contract.ps1`, which runs `deployment-lock-race-contract.ps1`, `app-rollout-contract.ps1`, `source-package-contract.ps1`, `offline-release-handoff-contract.ps1`, `workflow-context-contract.ps1`, `cloud-window-tests.ps1`, and `release-guards-contract.ps1`; the workflow then runs `./mvnw -B verify`.

CI run [35307790732](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/35307790732) completed successfully for the PR head, including the offline contract gate, Java/Maven verification, and Docker-backed integration verification. The PR-only Docker image and local Kind smoke jobs were skipped. This is local contract/build evidence only: no AWS, OIDC, CodeBuild, Terraform or `kubectl` deployment was attempted, and no APP runtime acceptance `PASS` is claimed. The APP R4 staging/production records in [manifest.json](manifest.json) remain `NOT_RUN`.

## Repository and CI evidence

| Repository | Reviewed revision | Local CI/contract evidence |
| --- | --- | --- |
| APP | `9725629b6666b60169447265f1f55e0cf85983d9` | Java 17 Maven wrapper/toolchain files, the exact offline release contract suite, `mvnw.cmd verify`, and `.github/workflows/ci-cd.yml`; CI run `35307790732` passed for the PR validation |
| FUN | `c2b2cdcf1d4101c268dbdc273549eae84fa0870d` | Maven/runtime tests, `tests/pipeline-contract.ps1`, and `.github/workflows/ci.yml`; merged push run `35315358633` reached the closed cloud-window deployment guard |
| K8S | `7991a322b7bb7f1ee70e11404f817e11e318d9b2` | Terraform/PowerShell contract suite, pinned Terraform/Helm setup, provider initialization and `.github/workflows/ci-cd.yml`; merged push run `35313930756` reached the closed cloud-window deployment guard |
| DB | `33b7da9172c45d766d36e7561d59ee289053b02b` | PostgreSQL/root Terraform tests, `tests/pipeline-contract.ps1`, and `.github/workflows/ci-cd.yml` |

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

Read-only GitHub API verification on 2026-09-18 confirmed that all four delivery repositories have protected `main` and `develop` branches (`enforce_admins: true`, force pushes and deletions disabled, and pull-request review protection configured) and `staging`/`production` environments with custom branch policies mapping `staging` to `develop` and `production` to `main`. The protection configuration has zero required approvals and no required status checks. This verifies repository policy configuration only: no screenshots or runtime R4 acceptance are captured, and cloud deployment, production, failure-recovery and observability acceptance remain `NOT_RUN`.

The K8S staging control-plane observation records only the base API/platform handoff. It is not evidence of application, functions, database, production, protected-route, telemetry, failure-recovery or eight-run acceptance. Live AWS deployment, measured capacity/costs, SES/SNS/New Relic enrollment and cleanup authorization remain outstanding.

### R5 — submission and publishing

The video, final PDF, external repository/reviewer access, video hosting and portal submission remain `NOT_RUN`. Prepared scripts and metadata may be reviewed locally, but no publication or submission is implied by this file.
