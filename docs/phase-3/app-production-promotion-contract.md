# APP production promotion contract

[production-contract.yml](../../.github/workflows/production-contract.yml) is a validation-only CI/CD boundary for pushes to `main`. Its job is skipped unless `vars.APP_PRODUCTION_DEPLOYMENT_ENABLED == 'true'` and uses the protected `production` environment. `develop` retains the separate staging workflow. No gate or GitHub protection setting is enabled by this change.

The job downloads one explicitly reviewed public GitHub Actions artifact, then runs [check-production-promotion.ps1](../../scripts/check-production-promotion.ps1). It has only `contents: read` and `actions: read`; it never obtains AWS credentials or executes a deployment. Success returns `PRODUCTION_CONTRACT_VALIDATED_DEPLOYMENT_DISABLED`. An optional, separately gated runtime preflight renders the real adapter without its execute switch. `start-deploy.ps1` still rejects the remote production launch; this change does not connect a production CodeBuild transport or cloud identity.

## Protected review inputs

| Variable | Review requirement |
| --- | --- |
| `APP_PRODUCTION_DEPLOYMENT_ENABLED` | Exact `true` to run the contract. Leave unset/false by default. The job-level expression needs a repository/organization variable; protect changes through repository administration. An environment-only variable is unavailable for selecting the job and does not open this gate. |
| `APP_PRODUCTION_ROLE_ARN` | Exact reviewed production role ARN in the same account as the images and input bundle. Store in the protected production environment. No role assumption occurs. |
| `APP_PRODUCTION_INPUTS_RUN_ID` / `APP_PRODUCTION_INPUTS_ARTIFACT_ID` | Explicit numeric IDs of one reviewed, nonexpired artifact in this repository. No latest-run or artifact-name fallback. |
| `APP_PRODUCTION_INPUTS_SHA256` | Independently reviewed SHA-256 of the exact `production-inputs.json` bytes in that artifact. Do not generate this review value from downloaded bytes inside the workflow. |
| `APP_PRODUCTION_RUNTIME_ENABLED` | Exact `true` selects the optional adapter preflight. Leave unset/false by default. It grants no cloud identity and does not supply the explicit execution switch. |

The remaining input variables belong in the protected production environment. Repository administrators must configure and verify main-only environment branch rules and required reviewer protections separately; naming the environment in YAML does not prove those protections exist. This contract does not claim to verify live GitHub settings, IAM trust or cloud receipt provenance.

## Public input artifact schema

At the artifact root, supply `production-inputs.json` with numeric `schemaVersion: 1` and scalar strings `environment: production`, `sourceCommit` (exact `github.sha`), `accountId`, `roleArn`, `projectName: oficina-phase3-oficina-app-production-deploy`, `sourcePrefix: releases/app/production`, and `deployerImageDigest` (`sha256:` plus 64 lowercase hexadecimal digits).

Each of the following fields is an object containing `path` (relative to the JSON directory, without escaping it) and `sha256` (reviewed exact bytes): `sourceArchive`, `releaseManifest`, `platformInputs`, `terraformVariables`, `cloudWindowEvidence`, `stagingPromotion`, and `stagingReleaseManifest`. Include every referenced public file in the artifact. The `stagingPromotion` object additionally requires `bucket`, `key: releases/app/staging/promotions/<sourceCommit>.json`, and the immutable S3 `versionId`. No secret values or credentials belong in these files.

`stagingPromotion` references the actual successful staging promotion JSON written to S3 by `start-deploy.ps1`, not the smaller local promotion-location descriptor. Reviewers must obtain the exact version and independently verify its hash/provenance before approving this input artifact. The offline validator verifies the pinned bytes and metadata; it does not contact S3 or prove that operator-supplied metadata is authentic.

The receipt must have schema 1, staging environment, `SUCCEEDED`, the exact staging CodeBuild project and build ID, immutable source/manifest/tfvars version IDs, canonical staging keys, and the same source commit/archive hash as the production release. Its manifest hash must match `stagingReleaseManifest`. The production release must contain boolean `promotedFromStaging: true` and matching `stagingArtifactSha256`, image/runtime digest, contract, migration and schema versions. Production platform, tfvars, cloud-window and deployer hashes must match the reviewed inputs. A production budget window is required; a staging billing acknowledgment cannot authorize production.

A merge that changes the commit SHA cannot reuse a receipt for a different SHA, even if trees match. The exact commit must first have successful staging evidence. Missing/expired artifact downloads, absent gate/role/receipt, scalar type errors, changed bytes, unsuccessful staging builds and commit/image mismatches all stop before any launcher.

## Private executor runtime adapter

[deploy-production.ps1](../../scripts/deploy-production.ps1) consumes the same reviewed bundle. It additionally requires the exact scalar `stateBucket` for the shared deployment lock, `mode: Compatible`, and identical `bootstrapImage`, `migrationImage` and `migrationSqlSha256` in the production and staging releases. The complete release must pass the existing real rollout contract, including production bootstrap secret **references/version IDs**, IRSA, database host, immutable image digests and the local migration SQL hash. It supports an already provisioned V8/phase3-v2 production workload with HPA minimum 2 / maximum 4; production initialization, FirstWriter and rollback are excluded.

The adapter requires `-Enabled true`, `-ProtectedEnvironment production`, the reviewed role/input hash and push-to-main context. It returns `PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED` unless both `-RuntimeEnabled true` and `-ExecuteReviewedPlan` are explicitly present. The workflow never supplies that switch. A caller's environment-name string is a context assertion, not independent proof of GitHub environment protection; a separately reviewed executor must preserve the protected job boundary.

[deploy.ps1](../../scripts/deploy.ps1) exposes the same path using `-ProductionEnabled`, `-ProductionRuntimeEnabled`, `-ProtectedEnvironment`, `-ProductionInputsFile`, `-ExpectedProductionInputsSha256`, `-ProductionRoleArn`, `-EventName` and `-BranchRef`. Execution also needs `-ApplyReviewedPlan` and no `-DryRun`. Existing source/manifest/deployer parameters must match the pinned bundle, as must the lock bucket, source key `releases/app/production/bundle.zip`, and exact bytes at `/tmp/oficina/app_production.tfvars.json`. The canonical backend metadata stays `app/production.tfstate` / `.tflock`, `us-east-1`; no Terraform root or apply is introduced.

An explicitly activated private invocation revalidates promotion, acquires the shared lock, verifies the existing workload, runs the reviewed bootstrap Job, checks its bounded receipt, and rolls out the pinned image. The production cloud window is checked before lock acquisition and each mutation; changed window bytes stop further writes. Direct production execution through `deploy-app.ps1` requires the same gates and bindings. Failure releases only the owned lock and does not trigger automatic rollback or publish promotion evidence.

## Validation and remaining boundary

Run `pwsh -NoProfile -File tests/production-promotion-contract.ps1`, `tests/app-rollout-contract.ps1`, or the existing `tests/pipeline-contract.ps1` suite. Fixtures are synthetic; AWS/Kubernetes commands are mocked, with no real production launches. Tests cover disabled defaults, missing gates/receipts, altered hashes, arrays/null/missing metadata, branch/event isolation, account/role mismatch, unsuccessful builds, changed windows, explicit executor invocation, migration failure and lock release.

Remaining activation prerequisites are the separately reviewed K8S production CodeBuild bundle transport and executor identity (the existing APP bridge is staging-only), genuine same-commit staging promotion evidence, reviewed public production inputs, a provisioned compatible production workload/RBAC, verified environment protections, and production cost authorization. No variables/settings/identity were enabled or changed. Local contracts supply no runtime readiness or R4 acceptance evidence; all cloud acceptance records remain unchanged. The [local LF checkpoint](evidence/app-staging-lf-activation-2026-09-18.md) remains historical local preparation evidence.
