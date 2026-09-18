# Executable staging FirstWriter contract

I6 owns migration and rollout through `scripts/deploy-app.ps1`. I7's
`scripts/deploy.ps1` now calls that real entry point. It never creates or applies
an empty `infra/environments/staging` root, the planned `infra/aws/staging` path,
or the existing local Kind root. K8S already owns the platform infrastructure,
gateway routes and reviewed workload renderer. This is an explicit refinement
of the I6 file layout, preserving its migration-before-rollout behavior without
introducing a second Terraform resource owner.

## Exact public input transport

The launcher accepts `PlatformInputsFile`, `StagingWorkloadFile` and
`CloudWindowEvidenceFile`. All are required for staging, including preflight.
The workflow supplies `APP_PLATFORM_INPUTS_PATH`, `APP_STAGING_WORKLOAD_PATH` and
`APP_CLOUD_WINDOW_EVIDENCE_PATH` from reviewed configuration. Release input JSON
must already contain these lowercase SHA-256 values; packaging preserves them:

| Exact file bytes | Release manifest field | Versioned object under `releases/app/staging/inputs/<sourceCommit>/` |
| --- | --- | --- |
| Public platform JSON | `platformInputsSha256` | `platform.json` |
| K8S-rendered workload JSON List | `stagingWorkloadSha256` | `workload.json` |
| Approved cloud-window JSON | `cloudWindowEvidenceSha256` | `cloud-window.json` |

No JSON reserialization occurs during upload. These files contain only public
configuration, reviewed metadata and Secret/IRSA references; credential values
remain in Secrets Manager/CSI. The manifest digest binds all three hashes, the
source archive hash, the executor image digest and `terraformVariablesSha256`.
The launcher rejects missing/mismatched files before AWS. It uploads each exact
file and requires a scalar, nonempty, non-null S3 VersionId before starting a build.

The closed CodeBuild override list adds exactly:

```text
PLATFORM_INPUTS_OBJECT_KEY   PLATFORM_INPUTS_VERSION_ID
STAGING_WORKLOAD_OBJECT_KEY STAGING_WORKLOAD_VERSION_ID
CLOUD_WINDOW_OBJECT_KEY     CLOUD_WINDOW_VERSION_ID
```

The K8S-owned `infra/modules/deployment-executor/main.tf` bootstrap must fetch
these exact object versions, validate each against its manifest hash, validate
the staging FirstWriter binding, and configure an isolated kubeconfig for the
reviewed EKS context. Its repository bootstrap invocation must additionally pass:

```powershell
-SourceArchiveFile <verified bundle.zip>
-PlatformInputsFile <verified platform.json>
-StagingWorkloadFile <verified workload.json>
-CloudWindowEvidenceFile <verified cloud-window.json>
-StateBucket <the same reviewed backend bucket>
-SourceKey releases/app/staging/bundle.zip
```

Existing manifest/source/deployer/tfvars/backend arguments remain mandatory and
retain their canonical paths: `/tmp/oficina/app_staging.tfvars.json`,
`app/staging.tfstate`, `app/staging.tfstate.tflock`, `us-east-1`. The tfvars file
remains a hash-bound, nonsecret executor configuration artifact; it does not
provide an arbitrary command or select a Terraform root. The state bucket is
also the deploy-app shared lock bucket; mismatched bucket identities fail.

## Execution and ownership

The adapter requires `-ApplyReviewedPlan`, `environment=staging`, and
`mode=FirstWriter`. It checks all immutable files and the open cloud window,
then invokes deploy-app's real render preflight before execution. Deploy-app
owns the shared lock through workload validation, zero-writer initialization,
bootstrap Job, migration receipt checks, rollout and HPA restoration. Existing
failure behavior stops rollout; the adapter does not bypass those checks or
expose raw external command output. The receipt is written under `app-rollout`
beside the executor's release manifest. Production and other release modes
remain closed through this entry point.

Platform prerequisites (namespace/RBAC, CSI, public runtime ConfigMap and its
pinned RDS CA, migration identity, networking and reviewed IRSA) remain K8S-owned
reviewed activation inputs. This change neither creates those resources nor
claims live readiness. The coordinated K8S bridge must be reviewed and installed
before activation; an older executor omits these paths and fails before rollout.
No published artifact is edited: regenerate inputs/manifest/source hashes and
publish new versions only through a separately authorized launch.

## Offline invocation regression

```powershell
pwsh -NoProfile -File tests/staging-first-deployment-contract.ps1 -ExecutorEntrypoint
pwsh -NoProfile -File tests/start-deploy-contract.ps1
pwsh -NoProfile -File tests/pipeline-contract.ps1
```

The entrypoint test invokes the actual deploy.ps1 and deploy-app.ps1, mocking
only AWS/Kubernetes process boundaries. It verifies the ordered lock, workload
creation, migration and rollout receipt; missing/changed input files and a
missing deploy-app file cannot succeed. Terraform is forbidden by the test,
and no empty directory is manufactured to stand in for an implementation.
