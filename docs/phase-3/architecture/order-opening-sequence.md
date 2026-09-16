# Order opening and delivery

```mermaid
sequenceDiagram
participant S as Staff
participant A as APP
participant P as PostgreSQL
participant O as Outbox publisher
participant Q as SQS FIFO
participant L as Notification Lambda
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
L->>P: delivery ledger/result
```

The separate publisher only observes committed rows; it does not run inside the request transaction. The outbox is durable intent, not exactly-once delivery. Recovery is inspected and operator-directed through the [outbox adapter](../../../src/main/java/com/oficina/adapter/out/outbox/OutboxNotificacaoAdapter.java) and the [canonical reporting runbook](../../runbooks/canonical-reports.md).
