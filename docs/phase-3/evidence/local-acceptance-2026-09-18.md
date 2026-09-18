# Local cross-repository acceptance checkpoint — 2026-09-18

**Result: `PASS_LOCAL_WITH_SKIPS` — 10 suites, zero failures.** Nine suites returned `PASS`; New Relic returned `PASS_STATIC_ONLY`. Dynamic Helm schema/render accounting was skipped because Helm was unavailable. This is local source/contract evidence only; it does not establish staging deployment or runtime readiness.

## Receipt and source provenance

The original local receipt is `D:\repository\phase3-local-acceptance-20260918-6.json`.
Its SHA-256 is `d8a90265a369860b56ed470cae7d83d8cf4e506623f81c55afdb39ce7a85a029`. The receipt and its ten referenced log hashes were verified during this documentation refresh. The receipt/logs are local artifacts, not published cloud receipts or reviewer-access evidence.

The run started at `2026-09-18T22:31:41.9517148+00:00` and finished at `2026-09-18T22:35:59.0088059+00:00`. It used isolated LF snapshots of these committed heads, with all four source worktrees recorded clean:

| Repository | Commit actually exercised |
| --- | --- |
| APP | `9a4ba4846334c72e459bb9a52ebf5f3176894b91` |
| FUN | `dbceabcf3dd9d11597af98825c3ca54159cbbf4f` |
| DB | `33b7da9172c45d766d36e7561d59ee289053b02b` |
| K8S | `22d60af8964f08844517c6768cf38323c27ee3d7` |

[APP PR #36](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/36) subsequently merged as `85a7227c94cf322cd1bdab3f2f0cb43099370a81`. The tested APP head was `9a4ba4846334c72e459bb9a52ebf5f3176894b91`; the harness scripts and contract test are unchanged between that head and the merge. This document does not reattribute the receipt to a rerun of the merge commit.

## Suite results

Every suite exited with code `0`. Commands below are copied from the receipt; `<empty-settings>` denotes the harness's generated credential-free Maven settings files.

| Repository | Recorded command | Result |
| --- | --- | --- |
| APP | `pwsh -NoProfile -NonInteractive -File tests/pipeline-contract.ps1` | `PASS` |
| APP | `mvnw -B -s <empty-settings> -gs <empty-settings> -Dtest=Phase3ContractTest,TokenTrustTest,ClienteIdentityTest,HealthGroupsTest test` | `PASS` |
| FUN | `pwsh -NoProfile -NonInteractive -File tests/verify-infrastructure.ps1` | `PASS` |
| DB | `pwsh -NoProfile -NonInteractive -File tests/verify.ps1` | `PASS` |
| K8S | `pwsh -NoProfile -NonInteractive -File tests/application-rollout-tests.ps1` | `PASS` |
| K8S | `pwsh -NoProfile -NonInteractive -File tests/platform-manifests-tests.ps1` | `PASS` |
| K8S | `pwsh -NoProfile -NonInteractive -File tests/newrelic-chart-tests.ps1` | `PASS_STATIC_ONLY` |
| K8S | `pwsh -NoProfile -NonInteractive -File tests/workload-capacity-tests.ps1` | `PASS` |
| K8S | `pwsh -NoProfile -NonInteractive -File tests/staging-app-workload-tests.ps1` | `PASS` |
| K8S | `pwsh -NoProfile -NonInteractive -File tests/runtime-public-configmap-tests.ps1` | `PASS` |

The New Relic result covers static assertions only. Repeating the dynamic chart checks with Helm remains necessary; `PASS_LOCAL_WITH_SKIPS` must not be reported as an unqualified full local pass. The APP Java selection is focused testing, not full Maven coverage or live integration acceptance.

## Unchanged cloud and submission gaps

The run skipped AWS identity/API access and deployment, Kubernetes cluster access/apply and runtime health, Terraform real plan/apply and remote state, private RDS grants/migrations/integration, and staging/production promotion receipts and R4 cloud acceptance. No AWS operation or cloud mutation was performed by this run or documentation refresh.

All eight records in the [R4 manifest](manifest.json) remain `NOT_RUN`; the [submission manifest](../submission/submission-manifest.json) remains `NOT_READY`. The [historical APP LF activation checkpoint](app-staging-lf-activation-2026-09-18.md) retains its original `f8bf2c2` source/image hashes and expired-SSO blocker. APP publication, a successful CodeBuild/promotion receipt, runtime readiness and R4 acceptance remain pending. This local harness result does not resolve those gaps.

For rerun requirements and guard limitations, see the [local acceptance guide](../local-acceptance.md).
