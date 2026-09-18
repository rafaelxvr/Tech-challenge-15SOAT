# Phase 3 implementation evidence audit

**Audit date:** 2026-09-17  
**Scope:** local source, tests, repository contracts and CI definitions in the four delivery repositories.

This audit records locally evidenced implementation work. It does not claim that AWS resources, protected environments, external publishing or end-to-end cloud acceptance exist.

## Verification refresh — 2026-09-18

The current develop revisions were rechecked from clean detached worktrees after the merged APP staging-executor input-binding and path-alignment fixes, FUN contract-byte fix, and K8S contract-test fix landed:

| Repository | Current reviewed revision | Verification evidence |
| --- | --- | --- |
| APP | `2c4b8072ebcb2cd050cad7f0cfa3a1f3ed4acd68` | Merged PRs [#21](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/21) and [#22](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/22) bind the reviewed Terraform variables/deployer digest and align guarded staging executor paths with the observed platform contract. APP pipeline, workflow, launcher, offline handoff, documentation-link, and pinned submission checks passed at this revision; the submission suite completed 27 checks, including deterministic `NOT_READY / FIXTURE_ONLY` PDF rendering with `reportlab==4.4.9`, `pypdf==6.10.0`, and `pypdfium2==5.13.0`. |
| K8S | `d8eb79ab260c4b40a55b80199d9158d5e5ed1b77` | Merged PR [#26](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/pull/26) adds the staging-only `releases/app/staging` source-prefix guard; the exact Terraform module tests passed 11/11, including rejection of the legacy prefix. Post-merge rehearsal [35348411176](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/actions/runs/35348411176) stopped staging and foundation-addons at closed-cloud-window validation before S3 upload, CodeBuild or Terraform; production was skipped. The live external foundation input correction remains pending. |
| FUN | `c2b2cdcf1d4101c268dbdc273549eae84fa0870d` | Cloud-window, lock, release-guard, secret-preparation, JWT-sync and workflow-context contracts passed locally; the merged push workflow also reached its deployment guard in CI run [35315358633](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/actions/runs/35315358633). |
| DB | `33b7da9172c45d766d36e7561d59ee289053b02b` | Bootstrap, cloud-window, lock, launcher, outputs, pipeline, source-package and workflow-context contracts passed locally. |

The local checks made no cloud calls. The merged FUN push workflow run [35315358633](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/actions/runs/35315358633) and K8S push workflow run [35313930756](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/actions/runs/35313930756) assumed their configured OIDC launcher roles, then stopped at the closed cloud-window guard before making S3, CodeBuild or deployment API calls; neither attempted a deployment. The post-merge K8S rehearsal [35348411176](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/actions/runs/35348411176) likewise stopped staging and foundation-addons before S3 upload, CodeBuild or Terraform, with production skipped. These runs provide workflow-guard evidence, while the local checks remain source and contract evidence only; no Terraform apply, Kubernetes API, external URL, video-hosting or student-portal operation was performed.

## APP offline release handoff receipt

The merged APP input-binding and path-alignment fixes are contract evidence only. PR #22 aligns the guarded staging adapter with the observed `/tmp/oficina/application_staging.tfvars.json`, `application/staging.tfstate`, and `application/staging.tfstate.tflock` paths while retaining the separate live CodeBuild source-location mismatch: the project points to `releases/application/staging/bundle.zip`, while the reviewed source contract remains `releases/app/staging/bundle.zip`. The workflow still requires the explicit staging gate, validates reviewed bindings with `start-deploy.ps1 -DryRun` before OIDC, and retains the fail-closed disabled path; no APP staging build or deployment occurred. The separate read-only staging control-plane observation reports no APP CodeBuild builds and an empty internal ALB target health list.

Merged PR [#11](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/11) landed as reviewed revision `6edc75898a4624e2969e0cd87238717865643b14`. The deterministic offline handoff packages that source revision, verifies the archive and canonical release manifest, renders the migration/rollout outputs, and writes a receipt with `status: INPUTS_VALIDATED_DEPLOYMENT_DISABLED`, `success: false`, and `deploymentAttempted: false`. The exact contract path is `tests/pipeline-contract.ps1`, which runs `deployment-lock-race-contract.ps1`, `app-rollout-contract.ps1`, `source-package-contract.ps1`, `offline-release-handoff-contract.ps1`, `workflow-context-contract.ps1`, `cloud-window-tests.ps1`, and `release-guards-contract.ps1`; the workflow then runs `./mvnw -B verify`.

CI run [35307790732](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/35307790732) completed successfully for the PR head, including the offline contract gate, Java/Maven verification, and Docker-backed integration verification. The PR-only Docker image and local Kind smoke jobs were skipped. This is local contract/build evidence only: no AWS, OIDC, CodeBuild, Terraform or `kubectl` deployment was attempted, and no APP runtime acceptance `PASS` is claimed. The APP R4 staging/production records in [manifest.json](manifest.json) remain `NOT_RUN`.

## Repository and CI evidence

| Repository | Reviewed revision | Local CI/contract evidence |
| --- | --- | --- |
| APP | `2c4b8072ebcb2cd050cad7f0cfa3a1f3ed4acd68` | Java 17 Maven wrapper/toolchain files, merged PR #21 input-binding and PR #22 staging-path alignment contracts, the exact offline release contract suite, the pinned submission suite, and `.github/workflows/ci-cd.yml`; no APP staging build or deployment was run |
| FUN | `c2b2cdcf1d4101c268dbdc273549eae84fa0870d` | Maven/runtime tests, `tests/pipeline-contract.ps1`, and `.github/workflows/ci.yml`; merged push run `35315358633` reached the closed cloud-window deployment guard |
| K8S | `d8eb79ab260c4b40a55b80199d9158d5e5ed1b77` | Terraform/PowerShell contract suite, 11 focused deployment-executor tests for the staging source-prefix guard, pinned Terraform/Helm setup, provider initialization and `.github/workflows/ci-cd.yml`; post-merge rehearsal `35348411176` reached the closed-cloud-window guard with staging/foundation-addons stopped and production skipped |
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

### Read-only deployment-input inventory — 2026-09-18

The GitHub environment secret listing was inspected by name and timestamp only; no secret values were read. APP staging and production have no listed deployment secrets. FUN staging lists `CLOUD_WINDOW_EVIDENCE_JSON` (`2026-09-18T03:40:13Z`) and `TERRAFORM_TFVARS_JSON` (`2026-09-17T23:53:09Z`); FUN production has none. K8S staging lists `CLOUD_WINDOW_EVIDENCE_JSON` (`2026-09-18T00:24:26Z`) and `TERRAFORM_TFVARS_JSON` (`2026-09-17T22:14:52Z`); K8S production has none. DB staging lists `CLOUD_WINDOW_EVIDENCE_JSON` (`2026-09-17T23:26:47Z`) and `TERRAFORM_TFVARS_JSON` (`2026-09-16T22:41:29Z`); DB production has none. The timestamps and secret contents must be refreshed or reviewed before staging, and the missing APP inputs must be supplied; this inventory does not change the R4 `NOT_RUN` status.

Read-only GitHub API verification on 2026-09-18 confirmed that all four delivery repositories have protected `main` and `develop` branches (`enforce_admins: true`, force pushes and deletions disabled, and pull-request review protection configured) and `staging`/`production` environments with custom branch policies mapping `staging` to `develop` and `production` to `main`. The protection configuration has zero required approvals and no required status checks. This verifies repository policy configuration only: no screenshots or runtime R4 acceptance are captured, and cloud deployment, production, failure-recovery and observability acceptance remain `NOT_RUN`.

The K8S staging control-plane observation records only the base API/platform handoff. It is not evidence of application, functions, database, production, protected-route, telemetry, failure-recovery or eight-run acceptance. Live AWS deployment, measured capacity/costs, SES/SNS/New Relic enrollment and cleanup authorization remain outstanding.

### R5 — submission and publishing

The video, final PDF, external repository/reviewer access, video hosting and portal submission remain `NOT_RUN`. Prepared scripts and metadata may be reviewed locally, but no publication or submission is implied by this file.
