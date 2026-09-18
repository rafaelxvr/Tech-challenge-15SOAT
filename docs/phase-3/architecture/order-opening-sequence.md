# Order opening and delivery

```mermaid
sequenceDiagram
participant S as Staff
participant A as APP
participant P as PostgreSQL
participant O as Outbox publisher
participant Q as SQS FIFO
participant L as Notification Lambda
participant D as DynamoDB delivery ledger
participant E as SES
S->>A: protected create/transition
A->>P: begin transaction
A->>P: write order, version and canonical history
A->>P: write outbox intent in same transaction
A->>P: commit
A-->>S: committed response
O->>P: poll committed pending outbox rows
O->>Q: publish committed event
O->>P: mark delivery attempt
Q->>L: one FIFO message
L->>D: Claim event lease and inspect order cursor
L->>P: Read current recipient view only
L->>E: Send eligible historical notification
E-->>L: Acceptance result
L->>D: Record terminal outcome and advance cursor
```

The separate publisher only observes committed rows; it does not run inside the request transaction. The outbox is durable intent, not exactly-once delivery. Recovery is inspected and operator-directed through the [outbox adapter](../../../src/main/java/com/oficina/adapter/out/outbox/OutboxNotificacaoAdapter.java) and the [canonical reporting runbook](../../runbooks/canonical-reports.md).

If SQS accepts a message but PostgreSQL publication tracking fails, publication can repeat. If SES accepts a send but DynamoDB completion fails, a retry can repeat the email after lease expiry. Neither retry repeats a business transition. The PostgreSQL recipient view is read-only; the delivery ledger is exclusively DynamoDB. See [RFC 004](../../rfcs/004-notifications.md), [ADR 004](../../adrs/004-outbox-delivery.md) and the [FUN residual-duplicate contract](../../../../oficina-functions/docs/notification-delivery.md).
