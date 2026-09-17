# RFC 001 — AWS profile

## Context

The phase requires cloud components while current evidence is local and no active deployment may be claimed.

## Alternatives

1. Keep a local-only demonstration with no cloud topology.
2. Use unmanaged virtual machines and manual configuration.
3. Use a bounded AWS study account with Terraform-managed EKS, RDS, Lambda, and gateway resources.

## Outcome

Choose option 3 when a separately approved deployment window exists. Terraform keeps resource intent reviewable and local validation never calls AWS. Until that window, this is a design outcome, not an active-environment claim.

## Implementation links

[Kubernetes handoff](../../../oficina-k8s-infra/README.md), [database handoff](../../../oficina-db-infra/README.md), and [functions handoff](../../../oficina-functions/README.md).
