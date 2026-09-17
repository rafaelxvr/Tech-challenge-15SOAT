# ADR 002 — Environment scaling

## Decision

Model staging and production as isolated Terraform/Kubernetes environment inputs, with HPA policy owned by K8S infrastructure.

## Consequences

Environment labels remain consistent across logs, transactions, and monitoring. Capacity measurements and active cluster evidence require a later deployment window and are not inferred from this repository.

## Implementation links

[Kubernetes architecture](../../../oficina-k8s-infra/docs/architecture.md), [monitoring Terraform](../../../oficina-k8s-infra/infra/monitoring/main.tf), and [APP environment configuration](../../src/main/resources/application.yml).
