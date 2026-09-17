# ADR 004 — Transactional outbox delivery

## Decision

Write the outbox with the service-order transaction and use a separate publisher plus FIFO consumer/delivery ledger.

## Consequences

Provider failure cannot roll back a committed order and retries are explicitly possible. Operations must inspect queue/DLQ and idempotency evidence instead of assuming exactly-once notification.

## Implementation links

[order sequence](../phase-3/architecture/order-opening-sequence.md), [publisher](../../src/main/java/com/oficina/adapter/out/outbox/OutboxPublisher.java), and [V7 migration](../../src/main/resources/db/migration/V7__criar_outbox_e_destinatario.sql).
