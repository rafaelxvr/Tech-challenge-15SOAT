# APP staging attempt — 2026-09-19

**Result: FAILED. Runtime readiness is not established.** Build `oficina-phase3-oficina-app-staging-deploy:02a7fcb8-2194-4327-b067-029ac8ffebde` ran from 03:50:48.484Z to 03:51:52.780Z using APP `8e9cba5f9d4b2b2ba2cfdda63c2b7c491a7af271` and the bridge rendered from K8S `f0238321e472204ac5628bee904b3ecec88ee6f9`. No promotion receipt was created. All eight [R4 records](manifest.json) remain `NOT_RUN`/`NOT_CAPTURED`; [submission](../submission/submission-manifest.json) remains `NOT_READY`.

This documentation refresh made no AWS calls. It records the preceding authorized staging attempt using saved nonsecret receipts. The historical [2026-09-18 LF checkpoint](app-staging-lf-activation-2026-09-18.md) remains unchanged; its expired-SSO result applies to that earlier attempt.

## Confirmed failure and cleanup

The [redacted EKS authenticator records](app-staging-attempt-2026-09-19/authenticator-redacted.json) show STS recognized the exact APP CodeBuild role/session, then EKS rejected it with **`identity is not mapped`** at 03:51:46–49Z. The APP executor has no EKS access entry. The request reached the private API, so endpoint connectivity and AWS identity were not the immediate failure; namespace RBAC was not reached.

The deployed wrapper reported `APP release kubectl operation failed: get` at `scripts/deploy-app.ps1:71`. It discarded kubectl stderr, so the exact subprocess exit code and individual `get` invocation are not retained. The first source-level read is `get deployment oficina-app -o json --ignore-not-found=true`, using context `arn:aws:eks:us-east-1:638612472889:cluster/oficina-phase3` and namespace `oficina-staging`. The source does not authorize workload writes before these checks. There is no successful workload/migration/health evidence.

The shared lock was acquired at 03:51:45Z (version `IU8V9wTYCg3u2ONTaHjeC30_I_HbfW4u`) and released at 03:51:52Z. Latest delete marker `dI9pSu.zTzTgzy50pBPZZjYPukppuk6n` confirms it is no longer active. The failed build was not retried. Production was untouched.

## Immutable publication and reviewed control-plane updates

All six versioned S3 inputs were downloaded by exact VersionId after publication and matched their reviewed SHA256. [The committed attempt receipt](app-staging-attempt-2026-09-19/attempt.json) records complete bucket/key/version/hash/size mappings and hashes of the original operator-held receipts. Bucket: `oficina-phase3-artifacts-16225b7358`; source version: `UO.3JHBKJ0X5kAM2YlcdHeJBTAcG0z_Z`.

| Input | Exact S3 VersionId | SHA256 |
| --- | --- | --- |
| Source | `UO.3JHBKJ0X5kAM2YlcdHeJBTAcG0z_Z` | `5fb3319b3603ee143f37001597c874a3e6ed1acd215e09a1170be742efa71bff` |
| Manifest | `NLq7Y5Q_ZrNrJX3FAjiLOzpxMFK9Sbgl` | `4c08e81b882c44e09c07d985e416d9a481dd6f0efa5522c7a9c5bf70a21190a9` |
| Public tfvars | `BckolZp_zUq3EK0KlNuX7EjPHEzDtuHV` | `bac520e56bffef0ecb7ce8fa4382e26bda77709cad0f334a546fdf3075f7a3c8` |
| Platform | `00UxxglYU..fN49BuU8xFnu36UxapVcp` | `44381c4fdad149a0549b9a93a53e773f8b66961ebbc5f9e0cb3d5682a93f0267` |
| Workload | `34tjPK6dVHRb3zTOPNwVEavhRGAdiks0` | `05ecc7af7d4b043d505704b20575855fc65807e8e8a14f5323ca1ea15b0524e8` |
| Cloud window | `zGsPWw7xRMFwr41IcsQEZoL73aGZ7esU` | `84ba23b9aa4c1e392a11d2db429dcb5357985b5680506c62a8fdf98ab6fd6115` |

APP and bootstrap images were published to `638612472889.dkr.ecr.us-east-1.amazonaws.com/oficina-phase3-app` at digests `sha256:e60a3f91756e77a5e2e0773a09aaf4c0f7e7ff0381b70671d0464b0c8694e39d` and `sha256:a1986b6a93cf1010f6a219a0b18d1d6d248e1525102ee9a0694bfd1f9d8b2458`. The APP image's JAR SHA256 is `57e0c44dc31447c639aad94ab8baeb72edd057df5cf48de9023dcc6f2f4a1092`. The reviewed deployer remains `sha256:e40c301b50bafa5cb78acf8da7e1f5a5d8615c4b0228c375dc536256a4949daf`.

Before launch, two independently reviewed APP-staging-only corrections passed fresh drift and post-read checks: source.buildspec update payload SHA256 `f0cccdb877aabf3d129a8637bd084c0a3fcc77f9ee5596ec06c7fa33ec303ca1` (review commit `4812d15205fab8262b57c23c6c698a610046c4ad`), and the exact shared-lock IAM additions payload SHA256 `a3d5fb80aa38181e45404c399ec5aae46979f99b34f0f0f830c29f3fb7be3629` (review commit `acba414033c74635e80704136b197d6e760a6a69`). IAM simulation allowed conditional acquisition with `s3:if-none-match=*` and denied missing/wrong conditions. Source, role and simulation receipt hashes are in the committed attempt receipt; no production role/project settings changed.

## Private inspection blocker

At 04:22:44.0497566Z, the saved account `638612472889` / region `us-east-1` inventory showed cluster `oficina-phase3` ACTIVE, version 1.35, public endpoint access false and private access true. Endpoint: `https://61E57F73801418DE7665FD5CA679704D.gr7.us-east-1.eks.amazonaws.com`; VPC `vpc-0a0fcb21e90c5b812`; private subnets `subnet-0e49c88c97a118461` and `subnet-06c013de9577c7f0b`. SSM reported **zero managed instances and zero active sessions**. Inventory SHA256: `d762287bd2513145362e77512f25c78b951a3472a034c6e560598fe4313c0694`.

The existing local context has no proxy; its bounded read of `oficina-staging/oficina-release-deployer` timed out during discovery. No existing private inspection path was found. Live Role and RoleBinding contents are therefore **unverified**. No new SSM session, diagnostic build, access entry or RBAC grant was created to bypass this blocker.

[K8S PR #35](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/pull/35), candidate head `df743c14defa0ffa81ac0d59347da41da02aaa85`, adds a separate staging STANDARD APP access entry with explicit IAM-ARN username and no EKS access-policy association. Its candidate/rollback/hash documents and 11 passing mocked Terraform tests are source evidence only. Live namespace RoleBinding verification and cloud application remain pending; no build retry is implied.

## Receipt locations and evidence boundary

The portable [attempt JSON](app-staging-attempt-2026-09-19/attempt.json) and [redacted authenticator JSON](app-staging-attempt-2026-09-19/authenticator-redacted.json) are committed for review. Original receipts are operator-held under `D:/repository/app-staging-activation-artifacts-8e9cba5`; their absolute paths and SHA256 values are recorded in `operatorHeldReceipts`. They are not public download links. The JSON includes the authenticated CloudWatch console URL for this exact build; it requires account access.

`published-input-receipts.json` SHA256 is `e9d1e6625e379a87477b032e3540f794c9798babb1f841f78ef31ae6b1731eff`; `terminal-build.json` is `7ba2c795b97dba9e76665db892dfdc7908059d5a4d5948874713bdf1dfb8196e`. Publication and corrected executor prerequisites are not runtime success. EKS rollout/health, SQL bootstrap/migration, application behavior, observability and successful promotion remain unaccepted. R4, reviewer access, video and submission readiness are unchanged.
