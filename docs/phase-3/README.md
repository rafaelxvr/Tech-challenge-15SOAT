# Phase 3 architecture and operations

This index describes the reviewed source state. No cloud deployment or public endpoint is claimed active.

- [Components](architecture/components.md), [authentication](architecture/authentication-sequence.md), [order opening](architecture/order-opening-sequence.md), and [data model](architecture/data-model.md)
- [API contract snapshot](api/contracts.md), pinned to APP `7ca6e2948e423ea171c252eddeaca266179bd153`
- [Release operations runbook](runbooks/release-operations.md) for bootstrap, interruption, recovery, evidence, and cleanup
- [APP staging activation checklist](staging-activation-checklist.md) for reviewed GitHub/OIDC inputs and post-run evidence
- [R4 cloud-window status](evidence/cloud-window.md) and [cleanup proposal](../runbooks/cleanup.md)
- [Offline submission/PDF guide](submission/README.md) and [submission manifest](submission/submission-manifest.json)
- [RFCs](../rfcs/001-aws-profile.md) and [ADRs](../adrs/001-modular-monolith.md)
- [Current reviewed source references](evidence/implementation-audit.md), with historical activation/receipt revisions kept separate.
- [Requirement/evidence matrix](evidence/requirements.md), including audited source revisions and explicit staged acceptance gaps.

Run local APP checks with `./mvnw.cmd -q test`; infrastructure deployment is an R4-authorized action only.

Repository architecture: [APP](../architecture.md), [FUN](https://github.com/rafaelxvr/Tech-challenge-15SOAT-functions/blob/dbceabcf3dd9d11597af98825c3ca54159cbbf4f/docs/architecture.md), [K8S](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/blob/22d60af8964f08844517c6768cf38323c27ee3d7/docs/architecture.md), [DB](https://github.com/rafaelxvr/Tech-challenge-15SOAT-db-infra/blob/33b7da9172c45d766d36e7561d59ee289053b02b/docs/architecture.md).
