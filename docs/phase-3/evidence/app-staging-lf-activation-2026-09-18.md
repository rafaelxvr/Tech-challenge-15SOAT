# APP staging local LF activation checkpoint

Recorded 2026-09-18 at 20:49 UTC. Status: **LOCAL_ARTIFACTS_READY_AWS_AUTH_BLOCKED**. This records local preparation only; it does not establish runtime readiness or R4 cloud acceptance. No AWS operations were performed for this documentation refresh.

## Reviewed source and local artifacts

| Input | Exact reference |
| --- | --- |
| APP source | `f8bf2c2ca1a6f80730619d52dbaa2ffb8d9cce8c`, merged [PR #33](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/33) on `develop` |
| K8S renderers/bridge | `22d60af8964f08844517c6768cf38323c27ee3d7`, merged [PR #32](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/pull/32) |
| Local package | `D:/repository/app-staging-activation-artifacts-f8bf2c2/ready-lf` |
| Local source worktree | `D:/repository/app-staging-activation-lf-f8bf2c2` |
| Intended staging project | `oficina-phase3-oficina-app-staging-deploy` |

At refresh, APP `origin/main` remained `440b4fc251d6c8e41c2596021f968f903fdcd485`; the approved activation source is the exact merged `develop` commit above. Local paths identify operator-held files, not published artifacts or portable download links. The package contains `activation-report.md` and `artifact-hashes.json`; the immutable values are transcribed below so review does not depend on access to that machine.

| Local Docker tag | Manifest-list digest (not yet verified in ECR) |
| --- | --- |
| `oficina-app:staging-f8bf2c2-lf` | `sha256:2e1f7dff0bf209d7d102c17ae3e455025e838da67b5756520e129556e1802a32` |
| `oficina-bootstrap:staging-f8bf2c2-lf` | `sha256:d8e56cca59e64d906e76b03c3e3f894ff1c077f30652a098379490ab276587e0` |

| File relative to local package | SHA-256 |
| --- | --- |
| `handoff/source.zip` | `709089ef1248c042c84a05e1a2f7c15cd369a0936481fb1234f8f2d93cf1f487` |
| `handoff/release-manifest.json` | `a0e997e0e81055b61834cc9128b9754da52a52d287c6e020d4444daa5609025a` |
| `app_staging.tfvars.json` | `f7e739678cc26e9c353e5f758aacad2e893b5e45ad19687b992cc5d316b44352` |
| `platform.json` | `b73e1a600d5463f0d4bcf74a20ef45d6f3673b831119141b09951eb8093f9564` |
| `k8s/app-workload-staging.json` | `a525136dcdb7c956061ceeef79635c1b75fbe8407e29592c10b52302c6728793` |
| `cloud-window.json` | `8e3501e6d0b14d46c27334c5e5b1f717250da57df47cfde19f00c814ba36dad2` |
| `k8s/runtime-public-staging.yaml` | `d847710c98c450b732e4c6213b6d945b9cca7694152d54afd47b39e1b4bc90c7` |

The release migration index digest is `db5de2abcc29e39b7542f749451de7d50789786758e3eb3f72df51fade375f06`. The reviewed public RDS CA digest remains `b1711d12bae51838581281e23b6cb97b1074016873b4dafc80ed14002462dd77`.

## Local validation and limits

1. Both `linux/amd64` Docker builds succeeded from the approved commit using LF files. They carried the full source revision label. These build commands used Maven skip-test flags; build success is not a new Java integration-test result.
2. Network-disabled, read-only container smoke checks passed: both images resolved UID/GID `10001`; the APP JAR and New Relic agent existed; bootstrap `sh -n /opt/oficina/entrypoint.sh` passed and `BootstrapMain.class` existed. No database bootstrap was executed.
3. [offline-release-handoff.ps1](../../../scripts/offline-release-handoff.ps1) and [start-deploy.ps1](../../../scripts/start-deploy.ps1) with `-DryRun` returned `INPUTS_VALIDATED_DEPLOYMENT_DISABLED`. This means input validation passed and deployment was not attempted.
4. The final ZIP was extracted independently: its shell entrypoint contained no CR bytes, and `Get-AppMigrationDigest` from the extracted scripts matched the release manifest. The public CA hash matched the reviewed pin.

Windows `core.autocrlf=true` affected the initial checkout and `git archive`; those initial candidate images failed the bootstrap shell syntax check. They remain preserved outside `ready-lf` and must not be published. The valid candidates use a new LF worktree and a process-only `core.autocrlf=false` Git override while packaging. No source or global Git configuration was changed. Regeneration must retain these LF settings and recheck archive/migration hashes.

## Pending cloud evidence

Read-only AWS metadata requests during the activation attempt failed with `Token has expired and refresh failed` for `study-process` / `us-east-1`. Therefore APP ECR/S3 publication, live bridge verification, guarded CodeBuild execution and promotion remain pending. No secret values were read. S3 VersionIds, APP build ID/status and promotion key/version/hash are **NOT_CREATED** for this package.

The package's reviewed window ends at `2026-09-18T21:06:25.4516938+00:00`. It is historical evidence, not authorization to run outside that interval. Before resuming, renew authentication, verify the live staging bridge and prerequisites, obtain renewed reviewed window evidence if needed, and regenerate the hash-bound manifest. Confirm pushed ECR digests against these candidates before any launch. Production remains prohibited.

The [central manifest](manifest.json) remains unchanged with R4 records `NOT_RUN`. Local images, source packaging and dry-run validation do not prove private RDS grants/migrations, Kubernetes rollout health, protected routes, observability, failure recovery or full cloud acceptance. See the [implementation audit](implementation-audit.md) for separately scoped historical platform receipts.
