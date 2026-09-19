# Staging public runtime ConfigMap artifact

The staging FirstWriter path now requires `RuntimePublicConfigMapFile`: a JSON representation of exactly the ConfigMap produced by K8S `scripts/render-runtime-public-configmap.ps1`. The existing renderer owns public values and validates RSA public keys and the pinned RDS CA. APP consumes those rendered values; it does not generate issuers, keys, certificates or environment settings. Historical six-input execution receipts are preserved. New staging packages contain seven versioned inputs.

## Render and review offline

Run the existing K8S renderer with the reviewed staging `CustomerPublicKeysFile`, `StaffKeyId`, `NotificationQueueUrl`, `HistoryZone`, `RdsCaFile` and `ExpectedRdsCaSha256`. Use a reviewed K8S checkout. Convert its YAML without contacting Kubernetes or AWS using that repository's existing provider-free Terraform decoder:

```powershell
# $k8sRepo and $renderedYaml identify the reviewed checkout and renderer output.
. "$k8sRepo/scripts/platform-manifest-contract.ps1"
$documents = Read-PlatformManifest $renderedYaml
if ($documents.Count -ne 1) { throw 'Exactly one rendered public ConfigMap required.' }
$json = $documents[0] | ConvertTo-Json -Depth 20
[IO.File]::WriteAllText($publicJsonPath, $json, [Text.UTF8Encoding]::new($false))
(Get-FileHash -LiteralPath $publicJsonPath -Algorithm SHA256).Hash.ToLowerInvariant()
```

Review the JSON bytes and digest; set `runtimePublicConfigMapSha256` in the release input before packaging. Pass the same file to `offline-release-handoff.ps1` and `start-deploy.ps1` using `-RuntimePublicConfigMapFile`. Offline handoff validates it and preserves exact bytes as `runtime-public.json`. It does not infer a digest from an unreviewed input in the workflow. The workflow requires protected configuration variable `APP_RUNTIME_PUBLIC_CONFIGMAP_PATH`; the existing cloud gate remains disabled unless explicitly enabled separately.

## Immutable transport

| Binding | Exact value/field |
|---|---|
| S3 key | `releases/app/staging/inputs/<sourceCommit>/runtime-public.json` |
| Manifest digest | `runtimePublicConfigMapSha256` |
| CodeBuild key/version overrides | `RUNTIME_PUBLIC_CONFIGMAP_OBJECT_KEY`, `RUNTIME_PUBLIC_CONFIGMAP_VERSION_ID` |
| CodeBuild digest override | `RUNTIME_PUBLIC_CONFIGMAP_SHA256` (must equal verified manifest field) |
| Artifact bucket | Existing reviewed `SOURCE_BUCKET` |
| Executor parameters | `RuntimePublicConfigMapObjectKey`, `RuntimePublicConfigMapVersionId`, `ExpectedRuntimePublicConfigMapSha256`, `ArtifactBucket` |

The launcher uploads the exact bytes and requires a nonempty scalar S3 VersionId. `deploy.ps1` obtains the new values from explicit parameters or the named environment overrides left by CodeBuild; the K8S bridge continues supplying its existing local paths. The APP executor downloads that exact version to `runtime-public-staging.json` beside the verified release manifest only after its explicit `-ApplyReviewedPlan` gate, source/tfvars/public-input checks and cloud-window check. It verifies returned VersionId, digest, JSON schema and CA before locking or touching Kubernetes. Missing inputs, wrong key/environment/version/hash, malformed JSON or download failure stop execution. Production rejects these staging transport arguments and retains its separate gates.

The source bucket, CodeBuild role and bridge remain K8S-owned; this PR grants no permissions or changes any live project. The current bridge leaves the additional environment overrides available to APP and its existing staging S3 input-prefix read covers the object. Any stricter separately deployed override policy must be reviewed before activation; an omitted binding fails closed. No unversioned download or implicit latest object is supported.

## Resource behavior

Current ownership is defined by the [platform prerequisite contract](staging-prerequisites-contract.md): the private foundation executor creates this ConfigMap; APP requires its verified receipt and matching live readback before migration. The original creation behavior described below is historical and is superseded for new FirstWriter executions.

Only `v1/ConfigMap` named `oficina-runtime-public-staging` in `oficina-staging` is accepted. Source labels must identify `oficina`/`oficina-k8s-infra`. Artifact top-level fields are limited to apiVersion, kind, metadata and data. Exactly nine data keys are allowed: customer public keys, staff issuer/audience/key ID, customer issuer/audience, notification queue URL, history zone and RDS CA. All values must be nonempty strings; binaryData, extra keys, private-key/credential markers, unresolved tokens, production issuers/queue and mismatched mounted CA bytes are rejected. The CA digest must equal the release's `bootstrapReview.caSha256`.

Within the existing shared deployment lock, `deploy-app.ps1` reads the ConfigMap with strict kind/name/namespace checks. Existing content must match every reviewed data value; drift stops without overwrite. If absent, Kubernetes CREATE installs it before the bootstrap Job. A competing creator causes failure rather than replacement. The adapter rereads and validates identity, labels and exact data, then runs migration and rollout. Public configuration is never applied from an arbitrary object list. No ConfigMap contents or native command output are logged; receipts carry the digest.

Default execution and launcher dry-run remain deployment-disabled. This change does not provision migration identity/networking, SecretProviderClass, Service, TargetGroupBinding, Secret objects or credential values. Existing failure handling releases the owned lock and leaves partial resources for explicit review; no automatic cleanup or retry is introduced. Runtime acceptance remains pending.

## Verification

RED: the new public-config test failed because its contract implementation was absent. GREEN: `tests/runtime-public-configmap-contract.ps1` covers scalar/schema/environment/CA/queue rejection, exact-version download, altered bytes and wrong returned VersionId. `tests/staging-first-deployment-contract.ps1` covers locked creation before migration, matching existing content, drift/malformed response rejection and the actual executor invocation; `tests/start-deploy-contract.ps1` covers immutable upload/override/receipt bindings and missing inputs. These are included in `tests/pipeline-contract.ps1`, alongside existing staging/production/lock/bootstrap contracts. All external AWS/Kubernetes calls are mocked. No AWS operation or build was started for this implementation.
