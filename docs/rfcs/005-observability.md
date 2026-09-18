# RFC 005 — Observability

## Context

The required API latency, Kubernetes resource, health, queue-failure, structured-log, and business dashboards need a common environment label without exposing credentials.

## Alternatives

1. Rely on host logs only.
2. Put provider keys and ad-hoc log formats in application configuration.
3. Emit safe JSON logs and named telemetry while Terraform supplies approved secret references and provider resources.

## Outcome

Choose option 3. APP/FUN report correlation-safe fields and K8S/FUN infrastructure owns provider wiring. Dashboards are provisioned intent and require a later approved deployment/evidence window.

## Implementation links

[APP telemetry](../../src/main/java/com/oficina/adapter/out/observability/NewRelicOrderTelemetry.java), [Kubernetes monitoring handoff](../../../oficina-k8s-infra/docs/architecture.md), and [functions monitoring handoff](../../../oficina-functions/docs/architecture.md).
