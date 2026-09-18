# ADR 001 — Modular monolith

## Decision

Keep APP as a modular monolith with explicit DDD boundaries for identity/access, workshop orders, catalog, and reporting/delivery.

## Consequences

Order, canonical history, and outbox intent can commit atomically. Modules must retain port/adapter boundaries so a later extraction does not couple controllers directly to persistence.

## Implementation links

[component map](../phase-3/architecture/components.md), [application layer](../../src/main/java/com/oficina/application), and [outbox adapter](../../src/main/java/com/oficina/adapter/out/outbox/OutboxNotificacaoAdapter.java).
