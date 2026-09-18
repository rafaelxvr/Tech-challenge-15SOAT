# APP staging activation contract

Status: **review specification; deployment disabled**. This contract defines the
nonsecret artifacts and acceptance checks required before a separately reviewed
activation. It does not enable the workflow gate, activate `scripts/deploy.ps1`,
modify CodeBuild, migrate Terraform state or authorize an apply. The metadata
checker is a test/review aid, not a production preflight or an attestation.

## Required GitHub inputs

All path inputs must resolve to reviewed files available after checkout. Record
the exact source commit and hashes; filenames alone are not approval evidence.

| Variable | Required binding |
| --- | --- |
| `APP_CLOUD_DEPLOYMENT_ENABLED` | Leave unset or different from `true` while the executor is disabled or any decision below is unresolved. Enabling is a separate reviewed action. |
| `APP_CLOUD_DEPLOYMENT_ROLE_ARN` | Reviewed staging GitHub launcher role, distinct from the CodeBuild service role and runtime APP IRSA role. Its trust must match the actual workflow OIDC subject and protected environment. |
| `APP_RELEASE_INPUT_PATH` | Release JSON with the fields below; packaging adds the source archive digest. |
| `APP_PLATFORM_INPUTS_PATH` | Reviewed platform JSON containing references and public configuration only, bound by `platformInputsSha256`. |
| `APP_CLOUD_WINDOW_EVIDENCE_PATH` | Fresh evidence accepted by `check-cloud-window.ps1`, including the approved allowance/billing basis and an open UTC window. Metadata inspection alone cannot authorize that window. |
| `APP_TERRAFORM_VARIABLES_PATH` | Reviewed JSON object for an implemented APP executor contract. The current local Kind Terraform root is not an AWS APP deployment contract. Do not supply an empty placeholder merely to pass JSON validation. |
| `APP_TERRAFORM_VARIABLES_SHA256` | Independently reviewed SHA-256 of the exact tfvars bytes, also present as `terraformVariablesSha256` in the release input. |
| `APP_DEPLOYER_IMAGE_DIGEST` | Independently reviewed `sha256:<64 lowercase hex>` digest. It must equal the CodeBuild project's pinned image digest and exist in that same ECR repository/account/region. A runtime APP digest or mutable image tag is not a substitute. |

## Release and platform artifacts

Release input includes `schemaVersion: 1`, `environment: staging`, `mode`,
`sourceCommit`, `contractVersion: phase3-v2`, `databaseSchemaVersion: V8`,
`migrationVersion: V8`, `image`, `runtimeArtifactDigest`, `previousImage`,
`migrationImage`, `kubeContext`, `migrationSecretName`, `migrationServiceAccount`,
`migrationSqlSha256`, `platformInputsSha256`, `terraformVariablesSha256`, and
`deployerImageDigest`. Do not predeclare `artifactSha256`:
`offline-release-handoff.ps1` creates it from the reviewed Git source archive.
Bind `runtimeArtifactDigest` to the approved runtime image and its build evidence.
An existing ECR tag with a source-looking name does not establish provenance.

The image, previous image and migration image must be immutable same-account
`us-east-1` ECR references. The migration image must actually support the rendered
Flyway `migrate` job; syntactic digest validation is insufficient. Select
`FirstWriter`, `Compatible` or `Rollback` from evidence of the current writer and
schema state. `Rollback` additionally needs the same-schema/security compatibility
receipt required by `app-release-contract.ps1`. The currently required
`previousImage` must not be invented for a first deployment: either provide a
reviewed reference with a justified role or separately change that contract.

The complete platform handoff has exactly these fields:

| Fields | Evidence source |
| --- | --- |
| `Environment`, `Image`, `DbHost` | Staging release provenance and current ECR/RDS metadata. |
| `AppIrsaRoleArn`, `DeployerPrincipalArn`, `PlatformBindingPrincipalArn` | Distinct reviewed IAM roles with the intended trust/permissions, not just existing ARN strings. |
| `DbCidr`, `AlbSubnetCidrOne`, `AlbSubnetCidrTwo` | Reviewed egress/ingress boundaries derived from the actual RDS and ALB subnet metadata. |
| `AppSecretArn`, `AuthorizerTrustSecretArn`, `NewRelicIngestSecretArn` | Exact staging Secrets Manager ARNs only. Do not read/copy secret values into these artifacts. |
| `NewRelicAccountId` | Reviewed nonsecret account metadata; never copied from a test fixture. |

The handoff also requires a DB V2 bootstrap receipt with immutable secret VersionIds
and a FUN gateway handoff, validated through `validate-staging-handoff.ps1`.
Secret metadata, an available RDS instance or a successful unrelated build cannot
stand in for completed bootstrap, grants or application acceptance evidence.

## Exact source and executor identities

The launcher and the CodeBuild project's automatic source download must agree:

| Binding | Required staging value |
| --- | --- |
| Project | `oficina-phase3-oficina-app-staging-deploy` |
| Artifact bucket | `oficina-phase3-artifacts-16225b7358` |
| Source prefix | `releases/app/staging` |
| CodeBuild S3 source location | `oficina-phase3-artifacts-16225b7358/releases/app/staging/bundle.zip` |
| Deployer ECR repository | `638612472889.dkr.ecr.us-east-1.amazonaws.com/oficina-phase3-deployer` |
| Executor tfvars path | `/tmp/oficina/app_staging.tfvars.json` |
| State / lock key | `app/staging.tfstate` / `app/staging.tfstate.tflock` |
| Backend region | `us-east-1` |

The `app` state namespace is intentionally aligned with the `app` source prefix.
Reject `releases/application/staging`, other environments, buckets or executor
projects; do not add a source-location override to hide project drift. S3
VersionIds belong to the exact uploaded object key. The platform owner must
correct any mismatched project source location through its separate reviewed
change.

## Deployer existence check without secret access

Before requesting activation, independently obtain the project's
`environment.image` and `source.location` using `codebuild batch-get-projects`.
Use `ecr describe-images` for the exact pinned digest in `oficina-phase3-deployer`,
account `638612472889`, region `us-east-1`. Use only nonsecret metadata queries;
do not query project credential values or Secrets Manager secret values.
`ImageNotFoundException`, an image tag instead of a digest, a different repository,
or disagreement with `APP_DEPLOYER_IMAGE_DIGEST` blocks activation. Finding another
available digest does not authorize substituting it in CodeBuild or the release.

For repeatable offline review, capture only this allowlisted JSON shape:

```json
{
  "schemaVersion": 1,
  "recordedAtUtc": "<actual UTC capture time>",
  "projectName": "oficina-phase3-oficina-app-staging-deploy",
  "sourceLocation": "<observed CodeBuild source.location>",
  "configuredDeployerImage": "<observed CodeBuild environment.image>",
  "reviewedDeployerDigest": "<independently reviewed APP_DEPLOYER_IMAGE_DIGEST>",
  "ecrRepositoryUri": "<observed ECR repositoryUri>",
  "ecrImageDigests": ["<observed digest from that repository>"]
}
```

These placeholders are deliberately not executable release values. Preserve
observed mismatches; do not rewrite a snapshot to make it pass. Record the query
date/provenance and review its age; the checker does not attest authenticity,
enforce a freshness window or prove image execution. Refresh live metadata before
any separately approved activation.

The snapshot root must be a JSON object with exactly the listed fields.
`schemaVersion` must be the integer `1`; timestamp, project, location, image,
reviewed digest and repository must be nonempty scalar strings. Arrays (including
singleton arrays), nulls, booleans and numbers cannot substitute for those strings.
`ecrImageDigests` must be an array of digest strings; an empty list cannot establish
image existence. Missing fields are rejected before any binding comparison.

Run `pwsh -File tests/staging-activation-contract.ps1 -MetadataFile <snapshot.json>`.
The checker performs no AWS request. It rejects missing/mismatched deployer
digests, wrong source locations and unexpected fields. A passing result is only
`METADATA_VALIDATED_DEPLOYMENT_DISABLED`, never deployment readiness or success.
The default no-file invocation exercises offline positive and negative fixtures;
`tests/pipeline-contract.ps1` runs it alongside the executable apply-guard tests.

## Unresolved decisions and implementation gates

| Gate | Required resolution |
| --- | --- |
| Runtime APP identity | Review/provision the staging APP IRSA role, trust and least-privilege permissions. Launcher, CodeBuild and FUN roles are not interchangeable with it. |
| Release mode and images | Decide `mode`, justify `previousImage`, supply an executable pinned `migrationImage`, and bind the runtime digest to reviewed build evidence. |
| New Relic metadata | Supply the actual reviewed account ID and exact ingest-secret reference without exposing its value. |
| APP tfvars and orchestration | Implement/review the real input schema and guarded migration/rollout/promotion orchestration. Current JSON-object validation is not a functioning deployment contract. |
| Executor drift and activation | Establish deployer-image existence, correct source-location drift through the platform owner, provide all reviewed files/hashes and fresh window evidence, then separately review activation. |

The read-only 2026-09-18 inventory observed CodeBuild pinned to
`sha256:d2e87101d2f1ae466a5156e9caa1803153447e3122c08031f75fbf77e94b2f13`;
ECR returned `ImageNotFoundException` for that digest. It also observed the wrong
`releases/application/staging/bundle.zip` source location. These are dated findings,
not hardcoded approvals or claims about subsequent platform changes. Resolve or
supersede each with fresh evidence.

`scripts/deploy.ps1` must continue throwing `APP_DEPLOYMENT_DISABLED` for invocation
without switches, with `-ApplyReviewedPlan`, or with both apply and dry-run.
Dry-run alone may return `INPUTS_VALIDATED_DEPLOYMENT_DISABLED`. Passing metadata
checks must never remove those guards. This specification changes no production
script, workflow variable, AWS configuration, production environment or apply path.
