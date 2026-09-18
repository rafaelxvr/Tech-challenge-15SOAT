# Phase 3 implementation evidence audit

**Audit date:** 2026-09-18
**Scope:** local source, tests, repository contracts and CI definitions in the four delivery repositories.

This audit records locally evidenced implementation work. It does not claim that AWS resources, protected environments, external publishing or end-to-end cloud acceptance exist.

## Verification refresh — 2026-09-18

Current source references below are APP `85a7227c94cf322cd1bdab3f2f0cb43099370a81` (PR #36 local acceptance harness, following the PR #37 documentation refresh and PR #35 contract baseline `7584afae5447b801bc98f825e14d774a73b2aa7c`), K8S `22d60af8964f08844517c6768cf38323c27ee3d7`, FUN `dbceabcf3dd9d11597af98825c3ca54159cbbf4f` and DB `33b7da9172c45d766d36e7561d59ee289053b02b`. These are reviewed source references, not new build or deployment receipts. The APP local LF activation package remains bound to `f8bf2c2ca1a6f80730619d52dbaa2ffb8d9cce8c`; earlier FUN/DB and K8S cloud receipts retain their original revisions and scope. See the [exact local activation checkpoint](app-staging-lf-activation-2026-09-18.md).

| Repository | Current reviewed revision | Verification evidence |
| --- | --- | --- |
| APP | `85a7227c94cf322cd1bdab3f2f0cb43099370a81` | Current source includes [PR #36](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/36) and the [local acceptance receipt](local-acceptance-2026-09-18.md); earlier source: [PR #35](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/35) and [PR #37](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/37). The following activation evidence belongs to historical revision `f8bf2c2ca1a6f80730619d52dbaa2ffb8d9cce8c`. Merged PR [#33](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/33) supplies the staging executor adapter and hash-bound public inputs. Local LF runtime/bootstrap images built successfully; image smoke checks, offline handoff, launcher DryRun and extracted-source migration hash checks passed. [Exact local hashes and limitations](app-staging-lf-activation-2026-09-18.md). APP ECR/S3 publication, CodeBuild build/promotion receipt and R4 cloud acceptance remain pending because AWS SSO expired; runtime readiness is not established. |
| K8S | `22d60af8964f08844517c6768cf38323c27ee3d7` | Merged PR [#32](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/pull/32) supplies the reviewed APP executor bridge. Its renderers produced the hash-bound local workload/public configuration used by the APP checkpoint. Live installation of this bridge was not reverified after SSO expiry. Earlier K8S staging receipts below concern their original revisions and do not prove APP runtime acceptance. |
| FUN | `dbceabcf3dd9d11597af98825c3ca54159cbbf4f` | Current source: [PR #27](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/pull/27). The following runtime/CI evidence remains attributed to historical revision `c2b2cdcf1d4101c268dbdc273549eae84fa0870d`. Cloud-window, lock, release-guard, secret-preparation, JWT-sync and workflow-context contracts passed locally. Workflow [35315358633](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/actions/runs/35315358633) rerun succeeded: the verify job passed, CodeBuild `oficina-phase3-oficina-functions-staging-deploy:9c61dab4-fcca-4a96-b1c2-68792d448d3f` reached `SUCCEEDED`, and current bundle/config/manifest objects are under `releases/functions/staging` after fresh cloud-window secret rotation; production was not run. This is FUN staging handoff evidence only; APP remains unrun and full runtime acceptance is incomplete. |
| DB | `33b7da9172c45d766d36e7561d59ee289053b02b` | Bootstrap, cloud-window, lock, launcher, outputs, pipeline, source-package and workflow-context contracts passed locally. DB staging remains the prior successful build/promotion at this reviewed revision; production was not run. |

The local checks made no cloud calls. The earlier FUN workflow attempt [35315358633](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/actions/runs/35315358633) stopped at the closed-cloud-window guard; its rerun after fresh cloud-window secret rotation passed verification and completed the FUN staging handoff with CodeBuild build `oficina-phase3-oficina-functions-staging-deploy:9c61dab4-fcca-4a96-b1c2-68792d448d3f` and current bundle/config/manifest objects under `releases/functions/staging`; production was not run. The earlier K8S push workflow run [35313930756](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/actions/runs/35313930756) and the first attempt of rehearsal [35348411176](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/actions/runs/35348411176) stopped fail-closed at the closed cloud-window guard before making S3, CodeBuild or deployment API calls. The K8S rerun completed the staging handoff with CodeBuild build `oficina-phase3-oficina-k8s-infra-staging-deploy:5872a110-eec6-4282-a214-b2e2fbab9dd4`, a versioned staging promotion receipt, successful foundation-addons, and a private executor Helm/Terraform refresh/apply reporting zero changed resources; production was skipped. These runs provide workflow-guard and platform-handoff evidence only, while the local checks remain source and contract evidence only; no APP staging build/deployment or full R4 runtime acceptance is established, and no external URL, video-hosting or student-portal operation was performed.

## Local cross-repository acceptance receipt

Merged APP PR [#36](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/36) is `85a7227c94cf322cd1bdab3f2f0cb43099370a81`. The [2026-09-18 local checkpoint](local-acceptance-2026-09-18.md) records receipt `D:\repository\phase3-local-acceptance-20260918-6.json`, SHA-256 `d8a90265a369860b56ed470cae7d83d8cf4e506623f81c55afdb39ce7a85a029`, with `PASS_LOCAL_WITH_SKIPS`: ten suites, zero failures, nine full local passes and one New Relic static-only pass. Dynamic Helm checks were skipped because Helm was unavailable. The receipt's tested APP head is `9a4ba4846334c72e459bb9a52ebf5f3176894b91`; the subsequent merge is not a new test run. Cloud checks were skipped, all R4 records remain `NOT_RUN`, submission remains `NOT_READY`, and the expired-SSO/runtime gaps below are unchanged.

## APP offline release handoff receipt

The merged APP input-binding, path-alignment and executable staging-adapter changes are source/contract evidence only. The guarded staging adapter now accepts the canonical `/tmp/oficina/app_staging.tfvars.json`, `app/staging.tfstate`, and `app/staging.tfstate.tflock` paths, matching the reviewed CodeBuild source prefix `releases/app/staging`. The workflow requires the explicit staging gate and validates reviewed bindings with `start-deploy.ps1 -DryRun` before OIDC. The adapter invokes `deploy-app.ps1` only for a guarded staging `FirstWriter` execution with reviewed source, image, public-input hashes and cloud-window evidence; production remains fail-closed. The latest attempt completed local preparation only: expired AWS SSO prevented publication and launch. The separate control-plane observation is historical and was not refreshed during this docs-only update.

Merged PR [#11](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/11) landed as reviewed revision `6edc75898a4624e2969e0cd87238717865643b14`. The deterministic offline handoff packages that source revision, verifies the archive and canonical release manifest, renders the migration/rollout outputs, and writes a receipt with `status: INPUTS_VALIDATED_DEPLOYMENT_DISABLED`, `success: false`, and `deploymentAttempted: false`. The exact contract path is `tests/pipeline-contract.ps1`, which runs `deployment-lock-race-contract.ps1`, `app-rollout-contract.ps1`, `source-package-contract.ps1`, `offline-release-handoff-contract.ps1`, `workflow-context-contract.ps1`, `cloud-window-tests.ps1`, and `release-guards-contract.ps1`; the workflow then runs `./mvnw -B verify`.

CI run [35307790732](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/35307790732) completed successfully for the PR head, including the offline contract gate, Java/Maven verification, and Docker-backed integration verification. The PR-only Docker image and local Kind smoke jobs were skipped. This is local contract/build evidence only: no AWS, OIDC, CodeBuild, Terraform or `kubectl` deployment was attempted, and no APP runtime acceptance `PASS` is claimed. The APP R4 staging/production records in [manifest.json](manifest.json) remain `NOT_RUN`.

## Repository and CI evidence

| Repository | Reviewed revision | Local CI/contract evidence |
| --- | --- | --- |
| APP | `85a7227c94cf322cd1bdab3f2f0cb43099370a81` | Current source includes [PR #36](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/36) and the [local acceptance receipt](local-acceptance-2026-09-18.md); earlier source: [PR #35](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/35) and [PR #37](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/37). The following activation evidence belongs to historical revision `f8bf2c2ca1a6f80730619d52dbaa2ffb8d9cce8c`. Merged PR [#33](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/33) supplies the staging executor adapter and hash-bound public inputs. Local LF runtime/bootstrap images built successfully; image smoke checks, offline handoff, launcher DryRun and extracted-source migration hash checks passed. [Exact local hashes and limitations](app-staging-lf-activation-2026-09-18.md). APP ECR/S3 publication, CodeBuild build/promotion receipt and R4 cloud acceptance remain pending because AWS SSO expired; runtime readiness is not established. |
| FUN | `dbceabcf3dd9d11597af98825c3ca54159cbbf4f` | Current source: [PR #27](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/pull/27). The following runtime/CI evidence remains attributed to historical revision `c2b2cdcf1d4101c268dbdc273549eae84fa0870d`. Maven/runtime tests, `tests/pipeline-contract.ps1`, and `.github/workflows/ci.yml`; rehearsal `35315358633` verify job passed and rerun CodeBuild staging handoff succeeded with current `releases/functions/staging` objects; production was not run |
| K8S | `22d60af8964f08844517c6768cf38323c27ee3d7` | Merged PR [#32](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/pull/32) supplies the reviewed APP executor bridge. Its renderers produced the hash-bound local workload/public configuration used by the APP checkpoint. Live installation of this bridge was not reverified after SSO expiry. Earlier K8S staging receipts below concern their original revisions and do not prove APP runtime acceptance. |
| DB | `33b7da9172c45d766d36e7561d59ee289053b02b` | PostgreSQL/root Terraform tests, `tests/pipeline-contract.ps1`, and `.github/workflows/ci-cd.yml`; prior successful staging build/promotion remains the recorded DB result, with production not run |

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

The [production promotion contract](../app-production-promotion-contract.md) adds a main-only, explicitly gated offline review job. Its negative tests require reviewed production inputs and a hash-pinned successful staging receipt for the same source commit. A valid contract still returns deployment-disabled; it does not invoke the production launcher, change any environment variable, or advance R4 acceptance.

The latest APP local activation checkpoint is `LOCAL_ARTIFACTS_READY_AWS_AUTH_BLOCKED`: expired SSO prevented APP publication and CodeBuild execution. No APP S3 VersionIds, build ID or promotion receipt were created. R4 cloud acceptance remains pending; local build/contract success does not establish runtime readiness.

All eight repository/environment records remain `NOT_RUN` in the [APP evidence manifest](manifest.json): APP staging/production, K8S staging/production, FUN staging/production and DB staging/production. The repository-specific status files remain explicit:

- [FUN R4 status](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/blob/dbceabcf3dd9d11597af98825c3ca54159cbbf4f/docs/evidence/r4-local-status.json)
- [K8S R4 status](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/blob/22d60af8964f08844517c6768cf38323c27ee3d7/docs/evidence/r4-local-status.json)
- [DB R4 status](https://github.com/rafaelxvr/Tech-challenge-15SOAT-db-infra/blob/33b7da9172c45d766d36e7561d59ee289053b02b/docs/evidence/r4-local-status.json)

### Read-only deployment-input inventory — 2026-09-18

The GitHub environment secret listing was inspected by name and timestamp only; no secret values were read. APP staging and production have no listed deployment secrets. FUN staging lists `CLOUD_WINDOW_EVIDENCE_JSON` (`2026-09-18T13:20:34Z`) and `TERRAFORM_TFVARS_JSON` (`2026-09-17T23:53:09Z`); FUN production has none. K8S staging lists `CLOUD_WINDOW_EVIDENCE_JSON` (`2026-09-18T13:20:33Z`) and `TERRAFORM_TFVARS_JSON` (`2026-09-17T22:14:52Z`); K8S production has none. DB staging lists `CLOUD_WINDOW_EVIDENCE_JSON` (`2026-09-18T13:20:35Z`) and `TERRAFORM_TFVARS_JSON` (`2026-09-16T22:41:29Z`); DB production has none. These are names and timestamps only; secret values were not read or recorded. Re-inspect all inputs before R4, and the missing APP inputs must still be supplied. This inventory does not change the R4 `NOT_RUN` status.

Read-only GitHub API verification on 2026-09-18 confirmed that all four delivery repositories have protected `main` and `develop` branches (`enforce_admins: true`, force pushes and deletions disabled, and pull-request review protection configured) and `staging`/`production` environments with custom branch policies mapping `staging` to `develop` and `production` to `main`. The protection configuration has zero required approvals and no required status checks. This verifies repository policy configuration only: no screenshots or runtime R4 acceptance are captured, and cloud deployment, production, failure-recovery and observability acceptance remain `NOT_RUN`.

The K8S staging control-plane observation records only the base API/platform handoff. It is not evidence of application, functions, database, production, protected-route, telemetry, failure-recovery or eight-run acceptance. Live AWS deployment, measured capacity/costs, SES/SNS/New Relic enrollment and cleanup authorization remain outstanding.

### R5 — submission and publishing

The video, final PDF, external repository/reviewer access, video hosting and portal submission remain `NOT_RUN`. Prepared scripts and metadata may be reviewed locally, but no publication or submission is implied by this file.
