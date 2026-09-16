# Components

```mermaid
flowchart LR
Customer --> Gateway[API Gateway]
Staff --> StaffAuth[Staff identity provider]
StaffAuth --> Gateway
Gateway --> App[APP modular monolith]
Gateway --> Auth[CPF challenge Lambda]
App --> RDS[(PostgreSQL)]
App --> Outbox[(transactional outbox)]
Outbox --> Publisher[separate outbox publisher]
Publisher --> SQS[SQS FIFO]
SQS --> Notify[notification Lambda] --> SES
Auth --> Dynamo[(DynamoDB)]
App --> NR[New Relic]
Notify --> NR
EKS[EKS workload] --> NR
```

The gateway is the only public route: it invokes CPF challenge/verification for customer tokens and sends staff requests carrying staff trust material through the same gateway into APP. APP keeps DDD boundaries for identity/access, workshop orders, catalog, and reporting/delivery. APP owns domain rules, Flyway, and the post-commit outbox publisher; FUN owns challenge/token and notification handlers; K8S owns gateway, EKS and Lambda resources; DB owns RDS boundaries.

Implementation evidence: [outbox publisher](../../../src/main/java/com/oficina/adapter/out/outbox/OutboxPublisher.java), [outbox adapter](../../../src/main/java/com/oficina/adapter/out/outbox/OutboxNotificacaoAdapter.java), and [API contract](../api/contracts.md).
