# Phase 3 Infrastructure and CI/CD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provision the approved AWS topology through four protected repository pipelines with reproducible, bounded deployments.

**Architecture:** Separate cloud bootstrap/foundation, environment platform, database, functions and application-binding states. GitHub builds/releases immutable artifacts; environment-specific CodeBuild jobs reach private endpoints. Terraform owns infrastructure resources, while the load-balancer controller owns target registration only.

**Tech Stack:** Terraform CLI 1.15.8, pinned/locked AWS/Kubernetes/Helm providers, GitHub Actions/OIDC, CodeBuild/S3, EKS/RDS/HTTP API/Lambda/SQS/DynamoDB/SES and IRSA.

**Spec:** [AWS](../specs/2026-09-14-phase-3-aws-design.md), [notifications](../specs/2026-09-14-phase-3-notifications-design.md), [observability](../specs/2026-09-14-phase-3-observability-design.md); [execution index](2026-09-15-phase-3-implementation.md).

## Global Constraints

FREE account only; no paid upgrade; US$35/48-hour window, US$80 project allowance and US$20 reserve. `us-east-1`, EKS 1.35, two `m7i-flex.large` workers, one NAT gateway, one internal ALB, two environment HTTP APIs, two private Single-AZ PostgreSQL 16.15 `db.t4g.micro` instances with 20 GiB gp3. Native S3 `use_lockfile`, versioning and separate state keys. No root deployment, no fixed AWS credentials in GitHub and no cloud apply while authoring I1–I7.

## Roots, files and public output interfaces

Each root contains `versions.tf`, `main.tf`, `variables.tf`, `outputs.tf`, `backend.tf`, `.terraform.lock.hcl` and `tests/*.tftest.hcl`; modules contain their focused resources/variables/outputs. Tests run `terraform init -backend=false`, `terraform validate`, `terraform test`, and `terraform fmt -check -recursive`. Provider-mocked tests use synthetic ARNs/IDs in fixtures only; release configuration uses verified account outputs.

| Owner | Roots/modules |
|---|---|
| K8S | `infra/bootstrap`, `infra/foundation`, `infra/environments/staging`, `infra/environments/production`, `infra/monitoring`; modules `bootstrap`, `network`, `cluster`, `deployment-executor`, `platform-environment` |
| DB | `infra/environments/staging`, `infra/environments/production`; module `postgres` |
| FUN | `infra/environments/staging`, `infra/environments/production`; module `functions` |
| APP | `infra/aws/staging`, `infra/aws/production`; `k8s/base`, `k8s/overlays/local`, `k8s/overlays/staging`, `k8s/overlays/production`, `k8s/jobs`; existing `infra/` Kind workflow remains separately named/local |

Each owner publishes `outputs.v1.json` with `schemaVersion=1`, `environment`, `sourceCommit` and only its named fields. I7's exporter filters `terraform output -json` against a committed allowlist; full state and secret values are never output artifacts.

| Producer | Fields consumed downstream |
|---|---|
| K8S foundation/environment | `vpcId`, `privateSubnetIds`, `databaseSubnetIds`, `clusterName`, `clusterOidcProviderArn`, `apiId`, `backendIntegrationId`, `healthIntegrationId`, `targetGroupArn`, `listenerArn`, `namespace`, `alertTopicArn`, `codeBuildProjects` |
| DB | `dbEndpoint`, `dbPort`, `dbName`, `masterSecretArn`, `databaseSecurityGroupId` |
| APP schema/bootstrap | `schemaVersion`, `appSecretArn`, `migrationSecretArn`, `authLookupSecretArn`, `notificationLookupSecretArn`, `authViewVersion`, `recipientViewVersion` |
| FUN | `authorizerId`, `customerPublicKeys`, `notificationQueueUrl`, `notificationQueueArn`, `notificationDlqArn`, `functionArns`, `artifactSha256` |

Inputs include actual account ID/human deployment role, GitHub owner/repository identities/visibility, deployment-role names, operator email, verified SES identities, a concrete UTC cloud window and current credit/eligibility evidence. Obtain these from the user's account/confirmed destinations at execution; do not put invented account IDs, emails or active URLs into release files. Unknown required input fails before apply. PR validation uses separate synthetic fixture values.

### I1: Implement protected state and federated bootstrap

**Files:** K8S Create `infra/bootstrap/`, `infra/modules/bootstrap/`, `infra/modules/bootstrap/tests/bootstrap.tftest.hcl`, `scripts/check-deployment-inputs.ps1`, `contracts/deployment-inputs.schema.json`, `docs/bootstrap.md`.

**Interfaces:** Bootstrap consumes verified repository/environment identities; produces state/artifact bucket names and per-repository/environment GitHub launcher-role ARNs. Terraform runtime credentials are external environment/session values, not HCL variables.

- [ ] **1 — Red state test.** Configure mock AWS provider and test resource assertions inside the owning module; run `terraform -chdir=infra/modules/bootstrap test`. Initially resources/outputs are absent. Validate the calling `infra/bootstrap` root separately; it consumes module outputs rather than referencing a child's resources directly.

```hcl
mock_provider "aws" {}
run "state_is_protected" {
  command = plan
  assert {
    condition = aws_s3_bucket_versioning.state.versioning_configuration[0].status == "Enabled"
    error_message = "State versions must survive an accidental overwrite."
  }
}
```

- [ ] **2 — Green state configuration.** Create versioned encrypted state/artifact buckets with public-access blocks and HTTPS-only policies, no `force_destroy`. Use partial backend configuration after one-time bootstrap-state migration:

```hcl
terraform {
  required_version = "= 1.15.8"
  backend "s3" { use_lockfile = true }
}
```

Each root gets its own `key`; caller needs Get/Put state and Get/Put/Delete its `.tflock`, not Delete on arbitrary state objects. Local bootstrap state is protected and migrated after backend creation. [S3 backend](https://developer.hashicorp.com/terraform/language/backend/s3)
- [ ] **3 — Green IAM checks.** GitHub OIDC trust uses exact repository/environment subject and `aud=sts.amazonaws.com`; environments restrict develop/main appropriately. Launcher roles can upload only their source prefix/start only their CodeBuild project. PR jobs have no role. Runtime roles differ from bootstrap. Input checker calls STS without printing credentials/account data and rejects an ARN ending `:root`; the user must establish a dedicated MFA human identity before bootstrap apply. Do not create Organizations/Control Tower/another account.
- [ ] **4 — Verify.** Run mock tests with wrong subject/absent required input cases, validate/fmt and inspect generated trust policies. No apply yet. Write the exact bootstrap/state-migration commands in `docs/bootstrap.md` with variables from the validated input file, never plaintext credentials.
- [ ] **5 — Commit.** Stage bootstrap/scripts/contracts/docs in K8S; `git commit -m "infra: define protected state and OIDC bootstrap"`.

### I2: Define network, fixed EKS capacity and private deployment executors

**Files:** K8S Create `infra/foundation/`, `infra/modules/network/`, `infra/modules/cluster/`, `infra/modules/deployment-executor/`, `infra/modules/cluster/tests/capacity.tftest.hcl`, `infra/modules/network/tests/network.tftest.hcl`, `images/deployer/Dockerfile`, `docs/network-capacity.md`.

**Interfaces:** Produces foundation fields in the output table. `codeBuildProjects` maps repository/environment to project/role names. IRSA trust is exact OIDC provider, audience and service-account subject; no wildcard namespace subject.

- [ ] **1 — Red capacity test.** Mock-provider tests run in each owning module: network asserts one NAT/private topology; cluster asserts private EKS API, two node groups and fixed group capacity. Test the approved node replacement strategy in the cluster module, where `aws_eks_node_group.workers` is declared:

```hcl
assert {
  condition = alltrue([for n in aws_eks_node_group.workers :
    n.scaling_config[0].min_size == 1 &&
    n.scaling_config[0].desired_size == 1 &&
    n.scaling_config[0].max_size == 1 &&
    n.update_config[0].update_strategy == "MINIMAL"])
  error_message = "A surge node would exceed the observed vCPU quota."
}
```

- [ ] **2 — Green network/cluster.** One VPC, two AZs, public NAT subnets, private workload/CodeBuild subnets and isolated DB subnets. Add S3/DynamoDB gateway endpoints, one NAT/EIP, no interface endpoints/public worker addresses. EKS 1.35, AL2023 x86_64, `m7i-flex.large`, 20 GiB gp3 per worker, one node group per AZ. Private-only API, MINIMAL/maxUnavailable=1 updates performed serially. Pin compatible add-on/AMI versions and enable VPC CNI network-policy enforcement; a YAML NetworkPolicy alone is not proof it is enforced.
- [ ] **3 — Green executors.** Eight short-lived CodeBuild projects/roles (four repos × two environments), VPC subnets/SG, Linux Small, no always-running runner. Build a platform-owned deployer image on GitHub with pinned PowerShell, Java17, AWS CLI, Terraform1.15.8, kubectl and Helm, publish to a platform-owned ECR repository and pin its digest in CodeBuild. Do not assume a managed image contains PowerShell. Count this image inside the existing retained-image allowance. Source type S3, no GitHub credential in AWS, no privileged Docker requirement for deploy-only projects. One active build/project. Foundation/bootstrap creation uses reviewed AWS API access before private jobs exist; later cluster/DB phases use CodeBuild. Do not assume GitHub-hosted runners reach private EKS/RDS.
- [ ] **4 — Verify.** Run provider tests/validate/fmt, output-schema tests, and calculate requests versus allocatable memory/CPU/pod ENI limits in the capacity report. Initial hypothesis remains total 3.25 CPU/9 GiB requests including two app surge pods; real verification occurs in R4. Exercise node-update sequencing in configuration checks, not live replacement during planning.
- [ ] **5 — Commit.** Stage foundation/modules/tests/docs; `git commit -m "infra: define bounded EKS and private execution"`.

### I3: Provision private managed PostgreSQL and role bootstrap contract

**Files:** DB Create environment roots, `infra/modules/postgres/`, `infra/modules/postgres/tests/postgres.tftest.hcl`, `README.md`; APP Create `scripts/database/bootstrap-roles.sql`, `scripts/database/check-role-permissions.sql`, `src/test/java/com/oficina/repository/DatabaseRolesTest.java`.

**Interfaces:** DB exports endpoint/dbName/masterSecretArn/SG only. APP owns schema and distinct migration/app/auth/notification logins plus view grants; produces the four runtime credential secret ARNs after the bootstrap phase. Secret values are injected by the private job and never managed as Terraform `secret_string` or included in outputs.

- [ ] **1 — Red DB contract.** Assert PostgreSQL version/size, private address, encryption and disabled autoscaling under mocked plan:

```hcl
assert {
  condition = aws_db_instance.this.publicly_accessible == false &&
    aws_db_instance.this.instance_class == "db.t4g.micro" &&
    aws_db_instance.this.allocated_storage == 20 &&
    aws_db_instance.this.multi_az == false
  error_message = "Database must match the reviewed study profile."
}
```

- [ ] **2 — Green RDS.** Per environment: PostgreSQL 16.15 (reverify orderable minor before apply), gp3/encrypted 20 GiB, Single-AZ, backups 1 day, no storage autoscale, public endpoint, Proxy or replica. Use `manage_master_user_password=true`; ordinary app/function roles never receive its managed master secret. SG permits PostgreSQL only from approved app/function/bootstrap sources. Require JDBC `sslmode=verify-full` with the regional CA bundle pinned/verified during packaging.
- [ ] **3 — Red/green least privilege.** In a PostgreSQL integration test create the roles, run APP migrations and bind grants. Migration owns the app schema; runtime app has only needed table/sequence DML; auth has schema USAGE and SELECT only on `auth_cliente_snapshot`; notification has only recipient-view SELECT. Revoke inappropriate PUBLIC/default grants, and assert base-table SELECT/UPDATE are denied for function roles. Use idempotent role existence checks plus parameterized credential handling in the bootstrap job; never echo passwords into SQL logs.
- [ ] **4 — Verify.** DB mock plan/validate/fmt; APP `DatabaseRolesTest` with real PostgreSQL. Calculate max app connections (5/pod including publisher/report use) plus migration/functions/warm churn against the actual RDS limit at R4. No timeout/connection guess is accepted as a measurement.
- [ ] **5 — Commit.** Commit DB infra as `infra: define managed PostgreSQL environments`; APP role scripts/tests as `test: enforce database runtime privileges`.

### I4: Define environment ingress, Kubernetes isolation and services

**Files:** K8S Create staging/production roots, `infra/modules/platform-environment/`, `k8s/platform/`, `infra/environments/staging/tests/platform.tftest.hcl`, `tests/namespace-isolation.ps1`; APP Create stable `k8s/base/service.yaml`.

**Interfaces:** Namespace `oficina-staging`/`oficina-production`, fixed Service `oficina-app` port 8080; platform produces `apiId`, backend/health integration IDs and ALB target/listener refs. APP cannot create/rebind target groups. Platform applies binding only after the stable Service exists; an empty target group is valid during bootstrap.

- [ ] **1 — Red ownership test.** Render/plan asserts two distinct environment listeners/target groups/APIs, internal ALB, custom role-limited bindings and no application-owned ALB/target attachments. Add a Kind scenario that attempts staging→production access and expects failure while approved DNS/app→same-env-DB access succeeds.
- [ ] **2 — Green ingress.** Shared ALB/VPC link in foundation; per-environment listeners 8080 (staging) and 8081 (production), IP target groups targeting the application's 8080 Service, fixed `TargetGroupBinding`. Install pinned AWS LBC and metrics-server. Preserve the business servlet path and gateway request correlation:

```hcl
request_parameters = {
  "overwrite:path" = "$request.path"
  "overwrite:header.X-Gateway-Request-Id" = "$context.requestId"
}
```

The separate health integration overwrites path with `/api/actuator/health/readiness`. Preserve Authorization passthrough; it is a reserved mapping header and must not be rewritten. [HTTP API mapping](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-parameter-mapping.html)
- [ ] **3 — Green isolation.** Default-deny ingress/egress, scoped DNS, same-environment app→DB/SQS/AWS HTTPS paths, controller/metrics access and ALB→app only. Namespace deployment roles get namespace RBAC, not cluster-admin. Install pinned secret-store CSI/AWS provider and IRSA bindings without putting runtime secret values in Terraform state. Define requests for every sidecar/DaemonSet; account for both workers. Disable unneeded add-ons.
- [ ] **4 — Verify.** Terraform tests/rendering and Kind isolation tests (with a policy-enforcing local CNI); do not call a deny policy tested if Kind's default networking ignores it. Record actual ALB readiness thresholds and test protected/public paths in R4. No claim of end-to-end TLS across the approved private HTTP segment.
- [ ] **5 — Commit.** Stage platform/policy/isolation tests and APP Service in their owners; `git commit -m "infra: isolate environment ingress and workloads"`.

### I5: Define functions, FIFO/DynamoDB resources and auth routes

**Files:** FUN Create environment roots, `infra/modules/functions/`, `infra/modules/functions/tests/functions.tftest.hcl`, `docs/runtime-permissions.md`.

**Interfaces:** Consumes platform API/network refs, APP view/credential refs and F4/F5 package SHA-256. Produces authorizer/public keys/queue/function outputs. Initialize secrets securely outside Terraform values; authorizer never gets customer signing private key.

- [ ] **1 — Red queue/authorizer test.** Assert FIFO encryption, 4-day source/14-day DLQ retention, 120-second visibility and 5 receives. Assert no function URL, result cache, reserved/provisioned concurrency or duplicate Terraform owner for gateway routes.
- [ ] **2 — Green queue/functions.** Configure one source FIFO and FIFO DLQ, separate challenge/delivery DynamoDB tables per environment with TTL, source mapping batch 1 and maximum concurrency 2. Notification is 1024 MiB/20 seconds. Auth starts at 1024 MiB/20 seconds and is benchmarked against gateway timeout and shared quota before acceptance; the total runtime still fits the approved 200,000 GB-second envelope. Restricted execution roles, VPC lookup, SES sandbox sender; logs retained 1 day.
- [ ] **3 — Green authorizer/HTTP bindings.** HTTP API v2 REQUEST authorizer, simple responses, TTL 0 and **no configured identity sources**. F4 returns `{"errorMessage":"Unauthorized"}` for absent/invalid bearer, `{"isAuthorized":false}` for an authenticated forbidden route and true for permitted routes. This is the documented path to preserve explicit 401 behavior. Add invoke permission scoped to the exact API/authorizer. Functions own auth routes; APP owns business routes and platform owns `/health`. Start environment gateway rate=1 request/second and burst=2; R4 verifies throttling and adjusts only within account/runtime limits. [AWS authorizer response behavior](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html)
- [ ] **4 — Verify.** Mock plan/validate/fmt and IAM policy assertions: auth lookup cannot mutate DB, notification can read/delete only its queue and update only its ledger, signer private key is scoped to verification, publisher can only SendMessage to its environment queue. Recount all secrets against the approved 16-secret allowance; no new role may silently broaden the credential bundle to hide a count excess.
- [ ] **5 — Commit.** Stage function infra/tests/docs; `git commit -m "infra: define authenticated serverless delivery"`.

### I6: Define application rollout, migrations and artifact bindings

**Files:** APP Create `infra/aws/staging/`, `infra/aws/production/`, `k8s/base/deployment.yaml`, `k8s/base/hpa.yaml`, `k8s/base/pdb.yaml`, environment overlays, `k8s/jobs/migrate.yaml`, `scripts/deploy-app.ps1`, `docs/runbooks/first-writer-cutover.md`; Modify Dockerfile and local Kind configuration; Test `tests/deployment-contract.ps1`.

**Interfaces:** Deployment consumes immutable image digest, DB/app credentials, queue URL and public customer keys; exports only schema/runtime/API references. Migration Job uses its distinct role/credential and the same release artifact's migration scripts, before app rollout. Cloud application startup disables Flyway automatic migration; local profile retains explicit development behavior.

- [ ] **1 — Red manifest contract.** Render both overlays and assert image contains `@sha256:`, per-pod requests 250m/768Mi, limits 1CPU/1GiB, Hikari max5, staging HPA1–2/prod2–4 at CPU60%, production PDBminAvailable1 and no plaintext secrets. Assert cloud pods cannot auto-run migrations.
- [ ] **2 — Green manifests.** Add separate startup/liveness/readiness probes as R1 defines, immutable images, IRSA/service-account/secret refs and environment-specific configuration. Install public keys before activating a new signer; staff HMAC secret remains separate. App root binds only versioned business routes after authorizer exists; preserve explicit scopes and compatibility aliases, omit retired email mutation.
- [ ] **3 — Green migration/cutover script.** On first schema writer cutover, deliberately drain old app writers, run the migration/bootstrap Job, verify schema/views/roles, then start new writers with Recreate. Document and measure the interruption. Later compatible releases use rollout only after migration success. On migration failure stop rollout; on post-deploy failure permit only the last schema/security-compatible artifact, never the old insecure writer. Do not reverse V5–V8 destructively.
- [ ] **4 — Verify.** Run `kubectl kustomize k8s/overlays/staging`, the manifest contract script, and a disposable Kind upgrade from V4 with old-writer drain. Verify the target Service/binding comes up, migration failure prevents app release, and no resource exceeds the combined capacity envelope at configured max/surge.
- [ ] **5 — Commit.** Stage manifests/scripts/Dockerfile/tests/runbook; `git commit -m "deploy: sequence migrations and immutable app rollout"`.

### I7: Implement branch-driven pipelines and verifiable output promotion

**Files:** Each repository Create/Modify `.github/workflows/ci-cd.yml`, `buildspec.deploy.yml`, `scripts/deploy.ps1`, `scripts/start-deploy.ps1`, `scripts/export-outputs.ps1`, `contracts/outputs-allowlist.json`, `tests/pipeline-contract.ps1`; K8S Create `scripts/check-cloud-window.ps1`, `docs/deployment-sequence.md` and branch-protection configuration/evidence instructions.

**Interfaces:** Launcher accepts `Environment`, `SourceZip`, `ExpectedSha256`, resolved `ProjectName`, S3 bucket/key. Output exporter emits only the fields defined above. Window check consumes a confirmed start/end, allowance and current account evidence and refuses launch when closed. Per-state concurrency plus a shared-foundation coordination lock prevents cross-repository overlap during shared changes.

- [ ] **1 — Red workflow tests.** Assert develop→staging/main→production mapping, PR checks with no deploy identity, `cancel-in-progress:false`, immutable artifact source and an explicit non-success result when the cloud window is closed. Test output allowlist rejects credential/state fields and launcher refuses wrong SHA/project/environment.
- [ ] **2 — Green source pinning.** Build on GitHub using short-lived OIDC; upload the reviewed zip to the exact repository/environment prefix, compute SHA-256 and use returned S3 VersionId as CodeBuild `sourceVersion`. The platform-owned project's inline bootstrap buildspec downloads/checks that object version before invoking the repository's `scripts/deploy.ps1`; do not execute an unverified source-bundle buildspec as the checksum guard. Create `scripts/deploy.ps1` in each owner to run its planned root/migration/rollout commands. Wait for build completion and propagate failure to GitHub; a successful StartBuild API call is not a successful deploy.

```powershell
$digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $SourceZip).Hash.ToLowerInvariant()
if ($digest -ne $ExpectedSha256) { throw 'Source digest mismatch' }
$uploaded = aws s3api put-object --bucket $Bucket --key $Key --body $SourceZip | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not $uploaded.VersionId) { throw 'Versioned upload failed' }
aws codebuild start-build --project-name $ProjectName --source-version $uploaded.VersionId
if ($LASTEXITCODE -ne 0) { throw 'Deployment launch failed' }
```

Project S3 source location must be the exact bucket/key written by this launcher; the version does not choose a different key. Log safe digest/run metadata only. [S3 source versions](https://docs.aws.amazon.com/codebuild/latest/APIReference/API_ProjectSourceVersion.html)
- [ ] **3 — Green promotion/protection.** Release manifest records source commit, image/JAR digest, contract and migration version. Production consumes the staging-tested manifest; a content-changing merge goes through staging again. Configure actual owner/visibility-compatible GitHub branch protections and environment branch policies at the external setup stage, with required PR checks/review and restricted bypass. Do not claim workflow YAML creates protection or silently make repositories public to gain a feature.
- [ ] **4 — Verify bootstrap order.** Document and dry-run: human state/OIDC bootstrap → foundation/executors → platform/RDS → APP schema/role/view initialization → functions → stable Service/target binding + APP routes/rollout → monitoring bindings. State keys do not by themselves coordinate different roots; define a shared deployment lock in the existing state bucket with conditional create/delete and ownership token, no new paid lock service. Tests reject a concurrent shared mutation and never steal an active lock. Record eight actual environment/repository deployment runs only during R4.
- [ ] **5 — Commit.** Run each repo's relevant unit/contract/Terraform/Kind checks, commit workflow/scripts/tests in each owner with `ci: deploy reviewed environment artifacts`. External publishing/permissions and cloud apply wait for the concrete destinations/window in R4.
