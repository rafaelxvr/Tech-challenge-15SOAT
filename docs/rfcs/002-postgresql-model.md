# RFC 002 — PostgreSQL model

## Context

Orders need relational consistency, queryable status history, and reliable integration intent.

## Alternatives

1. Store the aggregate and history as unstructured documents.
2. Use an event-only store and rebuild every report.
3. Use PostgreSQL with Flyway migrations, foreign keys, optimistic versions, canonical history, and an outbox.

## Outcome

Choose option 3. It retains transactional order/history/outbox writes and supports indexed operational reports without making dashboard aggregates authoritative.

## Implementation links

[V6 versions/history](../../src/main/resources/db/migration/V6__versionar_agregados_e_historico.sql), [V7 outbox](../../src/main/resources/db/migration/V7__criar_outbox_e_destinatario.sql), [V8 indexes](../../src/main/resources/db/migration/V8__indexar_relatorios.sql), and [data-model evidence](../phase-3/architecture/data-model.md).
