# RFC 004 — Notifications

## Context

An order transition must not be lost when a provider is unavailable after the business transaction commits.

## Alternatives

1. Send email synchronously inside the request.
2. Publish directly to a queue before committing the order.
3. Commit an outbox intent with the order, then let a separate publisher send FIFO messages and a Lambda consumer deduplicate delivery.

## Outcome

Choose option 3. The response represents a committed business change; delivery remains at-least-once and is observable/recoverable rather than described as exactly once.

## Implementation links

[outbox publisher](../../src/main/java/com/oficina/adapter/out/outbox/OutboxPublisher.java), [order sequence](../phase-3/architecture/order-opening-sequence.md), and [function handoff](../../../oficina-functions/docs/architecture.md).
