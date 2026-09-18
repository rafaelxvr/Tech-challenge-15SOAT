# Phase 3 architecture and operations

This index describes the reviewed source state. No cloud deployment or public endpoint is claimed active.

- [Components](architecture/components.md), [authentication](architecture/authentication-sequence.md), [order opening](architecture/order-opening-sequence.md), and [data model](architecture/data-model.md)
- [API contract snapshot](api/contracts.md), pinned to APP `7ca6e2948e423ea171c252eddeaca266179bd153`
- [Release operations runbook](runbooks/release-operations.md) for bootstrap, interruption, recovery, evidence, and cleanup
- [APP staging activation checklist](staging-activation-checklist.md) for reviewed GitHub/OIDC inputs and post-run evidence
- [R4 cloud-window status](evidence/cloud-window.md) and [cleanup proposal](../runbooks/cleanup.md)
- [Offline submission/PDF guide](submission/README.md), [14-minute recording script](submission/video-script.md) and [unfilled NOT_READY manifest](submission/submission-manifest.json)
- [RFCs](../rfcs/001-aws-profile.md) and [ADRs](../adrs/001-modular-monolith.md)
- [Requirement/evidence matrix](evidence/requirements.md), including audited source revisions and explicit staged acceptance gaps.

Run local APP checks with `./mvnw.cmd -q test`; infrastructure deployment is an R4-authorized action only.

Repository architecture: [APP](../architecture.md), [FUN](../../../oficina-functions/docs/architecture.md), [K8S](../../../oficina-k8s-infra/docs/architecture.md), [DB](../../../oficina-db-infra/docs/architecture.md).
