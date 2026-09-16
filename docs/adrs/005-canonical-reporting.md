# ADR 005 — Canonical reporting history

## Decision

Use persisted ordered service-order history timestamps as the canonical source for status duration and daily-order reports.

## Consequences

New Relic snapshots and dashboards are operational views, not business truth. Indexes and schema constraints must stay aligned with the canonical query; operators reconcile anomalies using the runbook.

## Implementation links

[data model](../phase-3/architecture/data-model.md), [JDBC report adapter](../../src/main/java/com/oficina/adapter/out/relatorio/JdbcRelatoriosAdapter.java), and [reporting runbook](../runbooks/canonical-reports.md).
