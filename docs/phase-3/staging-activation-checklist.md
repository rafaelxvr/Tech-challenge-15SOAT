# APP staging activation checklist

Keep `APP_CLOUD_DEPLOYMENT_ENABLED` unset until every item below has durable evidence. The workflow is intentionally skipped while the gate is absent or any input is missing.

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

## Evidence after enabling

- Record the workflow run, CodeBuild build ID, S3 VersionIds, source/manifest digests, and the redacted staging promotion receipt.
- Verify APP migration, rollout, health, protected authentication, gateway/FUN integration, database connectivity, logs, metrics, traces, and recovery before marking APP staging `PASS`.
- Keep production disabled; do not reuse staging billing evidence for production.
