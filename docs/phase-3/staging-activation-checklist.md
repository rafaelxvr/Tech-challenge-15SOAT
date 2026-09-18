# APP staging activation checklist

Keep `APP_CLOUD_DEPLOYMENT_ENABLED` unset until every item below has durable evidence. The workflow is intentionally skipped while the gate is absent or any input is missing.

Use the [APP staging activation contract](app-staging-activation-contract.md) for
the complete input schemas, exact source/executor bindings, offline deployer-image
existence check and unresolved decisions. Passing that metadata check does not
activate the disabled executor.

## GitHub controls

- Protect `develop` with pull requests, required CI checks, and no direct commits.
- Bind the `staging` environment to `develop` and require the reviewed launcher role variable `APP_CLOUD_DEPLOYMENT_ROLE_ARN`.
- Confirm the OIDC trust subject is the exact `rafaelxvr/Tech-challenge-15SOAT` `develop` workflow subject.
- Record the ruleset/environment IDs and the successful PR check run.

## Reviewed inputs

- `APP_RELEASE_INPUT_PATH` points to a reviewed file available after checkout with `schemaVersion`, `environment: staging`, `contractVersion: phase3-v2`, `databaseSchemaVersion: V8`, and immutable runtime/migration references.
- `APP_PLATFORM_INPUTS_PATH` points to reviewed K8S/DB/FUN outputs with ARNs and endpoints only; it contains no secret values.
- `APP_CLOUD_WINDOW_EVIDENCE_PATH` points to fresh, redacted staging evidence with the approved billing acknowledgment and an open UTC window.
- The fixed artifact bucket, `releases/app/staging` prefix, and `oficina-phase3-oficina-app-staging-deploy` project match the reviewed K8S executor outputs.
- `APP_TERRAFORM_VARIABLES_PATH`, `APP_TERRAFORM_VARIABLES_SHA256` and `APP_DEPLOYER_IMAGE_DIGEST` bind the reviewed tfvars bytes and a pinned executor image that actually exists in the project's ECR repository. Resolve the APP input schema and rollout decisions before supplying these as activation-ready artifacts.

## Evidence after enabling

- Record the workflow run, CodeBuild build ID, S3 VersionIds, source/manifest digests, and the redacted staging promotion receipt.
- Verify APP migration, rollout, health, protected authentication, gateway/FUN integration, database connectivity, logs, metrics, traces, and recovery before marking APP staging `PASS`.
- Keep production disabled; do not reuse staging billing evidence for production.
