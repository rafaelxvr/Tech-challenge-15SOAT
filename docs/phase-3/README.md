# Phase 3 architecture and operations

This index describes the reviewed source state. No cloud deployment or public endpoint is claimed active.

- [Components](architecture/components.md), [authentication](architecture/authentication-sequence.md), [order opening](architecture/order-opening-sequence.md), and [data model](architecture/data-model.md)
- [API contract snapshot](api/contracts.md), pinned to APP `7ca6e2948e423ea171c252eddeaca266179bd153`
- [Release operations runbook](runbooks/release-operations.md) for bootstrap, interruption, recovery, evidence, and cleanup
- [RFCs](../rfcs/001-aws-profile.md) and [ADRs](../adrs/001-modular-monolith.md)
- Repository architecture revisions: K8S `73c2fae369c05b860a1f52f5322ff52919421b14`, FUN `5acf72d5b120e0451dee7e5aace151c4a70c1935`, DB `9420c959698d38d977cd0bdfc8ca504f1c6dd6c2`.

Run local APP checks with `./mvnw.cmd -q test`; infrastructure deployment is an R4-authorized action only.
