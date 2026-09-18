# Reviewed APP migration and rollout contract

Status: source-only I6 adapter. `scripts/deploy-app.ps1` renders locally by default; the I7 launcher remains opt-in and the APP Terraform adapter is staging-only with an explicit apply switch. No cluster rehearsal, schema upgrade, interruption measurement or cloud release is claimed. Run the offline [contract tests](../../tests/app-rollout-contract.ps1) with `pwsh -File tests/app-rollout-contract.ps1`.

## Inputs and ownership

The platform repository owns the Deployment, Service, HPA/PDB, probes, resource limits, IRSA, public-key ConfigMap, separate staff-secret reference and route bindings. APP patches only image, strategy, replica count, release annotations and the two schema-mutation settings using **strategic merge**, retaining platform configuration. The APP Job label is `oficina-migration`, so it cannot enter the APP Service endpoints.

Use the exact JSON parameter document reviewed for K8S `scripts/render-platform.ps1`: `Environment`, `Image`, `AppIrsaRoleArn`, `DeployerPrincipalArn`, `PlatformBindingPrincipalArn`, `DbHost`, `DbCidr`, both ALB CIDRs, the three secret ARNs and New Relic account ID. Preserve the bytes; `platformInputsSha256` binds that whole document. APP validates the fields it consumes, while the K8S renderer validates its full contract. Both must pass before execution. APP uses database `oficina` with `sslmode=verify-full` and the platform's `rds-ca.pem`.

The separately reviewed release JSON has these fields:

| Field | Required value |
| --- | --- |
| `schemaVersion`, `environment`, `mode` | `1`; `staging` or `production`; `FirstWriter`, `Compatible` or `Rollback` |
| `sourceCommit`, `contractVersion`, `databaseSchemaVersion` | Exact reviewed source commit; `phase3-v2`; `V8` |
| `platformInputsSha256`, `migrationSqlSha256` | SHA-256 of platform JSON; canonical V1–V8 SQL index from `Get-AppMigrationDigest` |
| `image`, `previousImage`, `migrationImage`, `bootstrapImage` | Same-account `ACCOUNT.dkr.ecr.us-east-1.amazonaws.com/REPOSITORY@sha256:DIGEST`; current intended APP, expected existing APP, retained legacy migration reference, and dedicated Java BootstrapMain image |
| `bootstrapReview` | Exact `BootstrapReview` v1 object: source/environment-bound database host and CA hash plus the managed master and four runtime ARN/immutable VersionId references; no credential values |
| `kubeContext` | Explicit reviewed EKS ARN, same account/region; the caller's default context is ignored |
| `migrationSecretName`, `migrationServiceAccount` | Both `oficina-migration-ENVIRONMENT`, distinct from APP identity |
| `rollback` (Rollback only) | `image`, `databaseSchemaVersion: V8`, `contractVersion: phase3-v2`, `compatibilityEvidence` reviewed artifact/reference |

`ExpectedReleaseSha256` is supplied independently by the reviewer. A checksum binds reviewed bytes, not reviewer identity: the future launcher must resolve these from versioned approved receipts, enforce branch/window/lock rules and bind `sourceCommit` to the verified source archive and APP image provenance. The renderer does not fabricate that attestation. Migration SQL is copied from that source archive into an immutable ConfigMap; its digest covers each sorted filename and SHA-256, joined as `filename:sha256\n`. Historical migrations are unchanged.

The dedicated `docker/bootstrap/Dockerfile` image supplies Java 17, `BootstrapMain`, the locked runtime dependencies and a wrapper that verifies the review digest, runs the role/bootstrap/V1–V8 sequence and emits the reference-only receipt. It runs non-root with a read-only root filesystem and only the review, public CA, receipt and `/tmp` mounts writable. The immutable image digest and release review bytes are independently pinned. `BootstrapMain` disables Flyway clean/baseline/out-of-order, validates migrations, has no retry through the Job (`backoffLimit: 0`), a 600-second deadline, and receives no credential values or Kubernetes service-account token. The retained `migrationImage` field remains part of the release schema for compatibility evidence; the writer path executes `bootstrapImage`.

## Offline review

1. Save the independently reviewed release and exact platform renderer input JSON locally; compute the SQL digest with `. ./scripts/app-release-contract.ps1; Get-AppMigrationDigest`. Review the source/image/SQL relationship before recording the final release-file hash.
2. Render without cluster access: `./scripts/deploy-app.ps1 -ReleaseFile ./review/release.json -ExpectedReleaseSha256 REVIEWED_SHA256 -PlatformInputsFile ./review/platform.json -OutputDirectory ./review/rendered`. Inspect the five JSON manifests and compare the patch to the rendered platform bundle.
3. Run `./tests/app-rollout-contract.ps1` and `./tests/pipeline-contract.ps1`. They replace kubectl with a deterministic mock; their receipts are temporary test data, never deployment evidence.

## Execution sequence and failure behavior

The `-ExecuteReviewedPlan` switch is only for the future reviewed executor after the prerequisites below are satisfied. It is not wired into GitHub Actions or the disabled cloud adapters. Hold the environment deployment lock throughout execution; reconcile GitOps/controllers that could restore replicas/HPA while draining. Use one fresh output directory per attempt and a uniquely reviewed release document per migration attempt.

```mermaid
sequenceDiagram
  participant E as Reviewed APP executor
  participant K as Existing platform namespace
  participant J as Migration Job
  E->>K: Check expected immutable image, IRSA and bounded HPA
  alt FirstWriter
    E->>K: Delete HPA, set Recreate and replicas zero
    E->>K: Wait for all old APP pods to disappear
  else Compatible or Rollback
    E->>K: Require existing V8 and phase3-v2 annotations
  end
  alt Not Rollback
    E->>K: Create immutable review ConfigMap and Job
    K->>J: Run reviewed BootstrapMain image with IRSA and exact secret references
    J-->>E: Complete condition plus bounded V2 receipt proving V8/V5/V7
  end
  alt Successful migration or compatible rollback receipt
    E->>K: Strategic image patch with Flyway disabled and Hibernate validate
    E->>K: Wait for rollout; restore bounded HPA for FirstWriter
  else Failure
    Note over E,K: Stop; no image release or automatic rollback
  end
```

FirstWriter removes the HPA before setting replicas to zero and waits for **all** matching pods, including terminating pods, to disappear before creating the Job. Recreate remains the first-writer strategy. Compatible releases keep the HPA and require migration success before a bounded RollingUpdate (`maxSurge: 0`, `maxUnavailable: 1`). A reused completed Job cannot authorize rollout because the executor uses `create`, not `apply`, for Jobs. APP pods always set `SPRING_FLYWAY_ENABLED=false` and `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`.

Migration/drain failure stops without restoring writers or HPA. Post-rollout failure also stops without `rollout undo`; inspect restricted Job/executor evidence and recover deliberately. A new release receipt is required to retry a Job; no automatic deletion or repair is performed. Existing Job/ConfigMap cleanup is a reviewed retention operation after evidence capture. The local receipt starts `IN_PROGRESS` and becomes `ROLLOUT_COMPLETE` only after rollout and any HPA restoration succeed, preventing a failed retry from retaining an old successful receipt. Receipt timestamps bound the operation; actual first-writer interruption must be measured from external availability observations during the future rehearsal.

Rollback never runs reverse migrations. It requires a reviewed immutable target that has been demonstrated compatible with **the current V8 schema and phase3-v2 security contract**. The current Deployment must carry those same annotations and match the reviewed `image`; the target comes exclusively from `rollback.image`. A known insecure pre-cutover writer is not an eligible rollback artifact. A symbolic compatibility reference is an input requirement, not proof: the reviewer must inspect its test evidence before approving the release digest. If no compatible artifact exists, keep the environment stopped and roll forward.

## Unfulfilled activation prerequisites

### Canonical-history invariants retained from the original cutover contract

V6 is additive for stored data, but mixed old/new writers are unsupported. Stop accepting mutations, drain in-flight requests and background writers, then prove every old writer is stopped. The script drains the named APP Deployment; operators must identify and stop any writer outside that workload before executing it.

Record timestamp provenance before the cutover. Fresh synthetic fixtures explicitly use UTC and `HISTORICO_ZONA_COMPATIBILIDADE=UTC`. For an existing installation, establish the legacy writer's zone from deployment records/data provenance and explicitly configure it in the platform runtime ConfigMap. A workstation timezone is not evidence. If provenance is unknown, stop: never reinterpret or backfill timestamps using a guessed zone.

V6 preserves legacy timestamps, leaves canonical instants/sequences null, classifies populated staff references as STAFF and unrecorded actors as LEGACY_UNKNOWN, and marks existing orders incomplete. It removes only the old database-clock update trigger; new history/order compatibility times use the supplied application instant. Do not edit or destructively reverse historical migrations.

Before resuming mutations, check fresh order creation and its initial sequence 1, customer/staff foreign keys, UTC canonical time, optimistic conflict 409, and transactional stock/history rollback. New transitions on an incomplete legacy order start the canonical counter at 1 without making old history complete. Exclude incomplete history from complete-lifecycle reports until a separately reviewed reconciliation establishes both timezone and event order.

API compatibility remains unchanged: `criadoEm` history fields are preserved; `ocorridoEm` is an ISO-8601 Instant with explicit UTC (`Z`) for new history and absent/null for unresolved legacy history. New writes derive both from one Clock instant. Canonical sequence, not timestamp ties or UUID sorting, defines new order. These checks remain acceptance evidence to collect, not outcomes of the mocked rollout tests.

### Bootstrap, platform access and release activation

The initializer that securely creates separate roles/credentials, installs least-privilege grants/default privileges and proves schema/view/role-denial contracts is now the source Job contract. A successful Job must emit and pass the V2 receipt parser before any writer patch; an absent, malformed, mismatched or secret-bearing receipt leaves writers stopped. Existing V2/V4 development account seeds must be disabled/rotated by that reviewed bootstrap before traffic; this source task neither changes old migrations nor releases those credentials. Database V8, auth view V5 and recipient view V7 must be verified privately before enabling FUN/APP routes.

Platform prerequisites include the stable namespace/Deployment/Service/HPA (and production PDB), public key and pinned CA ConfigMap, separately provisioned migration Secret/service account, and a migration-specific NetworkPolicy allowing only DNS/database connectivity. APP's default-deny policies do not automatically permit this new pod label. Review narrowly scoped namespace RBAC for Job create/get/watch, immutable ConfigMap create, HPA delete/create/update, Deployment get/patch/watch, pod list and service-account get; current platform deployer RBAC must not be assumed to supply them. The renderer does not grant IAM, read secret values, initialize roles or create platform routes.

The [I7 activation prerequisites](../i7-pipeline-contracts.md) still apply: approved window, exact branch/OIDC identity, per-state concurrency/shared coordination where required, provenance, versioned output publication and production promotion. The disposable Kind V4→V8 upgrade, runtime-role denial tests, capacity envelope, migration-tool image validation and live availability/rollback rehearsal remain staged acceptance gaps. No production execution is authorized by a rendered file.
