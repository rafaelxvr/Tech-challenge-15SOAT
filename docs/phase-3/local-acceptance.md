# Credential-free local acceptance

Run from PowerShell 7 with Git, Java 17 on PATH, Terraform and kubectl installed. Helm enables dynamic chart checks; without it, the existing chart suite runs static assertions and the receipt explicitly records the dynamic checks as skipped. Existing Maven dependencies/providers/chart packages may be downloaded from public registries; this is a credential-free local check, not an air-gapped test.

```powershell
./scripts/phase3-local-acceptance.ps1 `
  -AppRepository D:/repository/app-checkout `
  -FunctionsRepository D:/repository/functions-checkout `
  -DatabaseRepository D:/repository/database-checkout `
  -KubernetesRepository D:/repository/kubernetes-checkout `
  -ReceiptFile D:/evidence/phase3-local-acceptance.json
```

All four distinct Git roots and a new receipt path are required. Each repository's committed `HEAD` is cloned locally into a disposable LF snapshot, preserving source worktrees and untracked files. Dirty source worktrees are disclosed but their uncommitted/untracked files are excluded. Commit first when a change must participate. The harness does not fetch, checkout, clean or change files in the supplied source repositories.

Suites execute in fixed order: APP offline pipeline contracts and focused `Phase3ContractTest`, `TokenTrustTest`, `ClienteIdentityTest`, `HealthGroupsTest`; FUN `verify-infrastructure`; DB `verify`; K8S application rollout, platform manifests, New Relic chart, workload capacity, staging workload and public ConfigMap contracts. Existing suites write only local test/build artifacts. Snapshots and logs remain in the reported temporary workspace for inspection; a repeated run uses a fresh workspace and receipt.

Child processes receive no inherited AWS credentials/profile, cloud tokens/passwords, kubeconfig or Terraform variable overrides. AWS configuration and Kubernetes configuration point to empty files. Inherited PATH shims reject real `aws` commands and Kubernetes API operations; only `kubectl kustomize` on snapshot directories is allowed. Terraform permits `fmt -check`, backend-disabled/read-only-lock initialization, validation and tests with mocked AWS providers. The existing K8S YAML decoder may use its exact provider-free console expression; DB may read an empty-resource synthetic state fixture, both only inside private suite scratch directories. Real plan/apply/destroy are rejected. Existing mock tools can simulate cloud commands without calling them. These guards protect reviewed suite entry points; they are not an OS sandbox for hostile scripts or absolute executable paths. Use reviewed repositories only.

The JSON receipt records exact commits, source scope, suite commands, start/end timestamps, exit codes, PASS/FAIL, local log paths/hashes and explicitly skipped checks. Output is `PASS_LOCAL_ONLY` only when every selected suite exits successfully without local skips; unavailable Helm yields `PASS_STATIC_ONLY` for chart assertions and `PASS_LOCAL_WITH_SKIPS` overall. Any suite failure produces a `FAIL` receipt and nonzero exit. The focused APP Java selection is not a full Maven/coverage or live integration acceptance claim.

Cloud identity/deployment, real Terraform state/apply, Kubernetes runtime health, private RDS grants/migrations, promotion receipts and R4 cloud acceptance are always marked skipped. Local PASS never establishes runtime readiness. Run `tests/phase3-local-acceptance-contract.ps1` to verify mandatory paths and command rejection without credentials.
