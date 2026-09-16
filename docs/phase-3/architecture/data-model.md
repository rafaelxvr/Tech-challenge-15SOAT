# Data model

```mermaid
erDiagram
    USUARIOS ||--o{ CLIENTES : usuario_id
    CLIENTES ||--o{ VEICULOS : cliente_id
    CLIENTES ||--o{ ORDENS_SERVICO : cliente_id
    VEICULOS ||--o{ ORDENS_SERVICO : veiculo_id
    USUARIOS ||--o{ OS_HISTORICO : alterado_por
    CLIENTES ||--o{ OS_HISTORICO : ator_cliente_id
    ORDENS_SERVICO ||--o{ OS_HISTORICO : os_id
    ORDENS_SERVICO ||--o{ OUTBOX_EVENTOS : os_id
    OS_HISTORICO ||--o| OUTBOX_EVENTOS : os_id_sequencia
    OUTBOX_EVENTOS ||--o{ OUTBOX_RECUPERACOES : event_id
    ORDENS_SERVICO ||--|| NOTIFICACAO_DESTINATARIO_SNAPSHOT : view_ordem_id
    CLIENTES ||--|| NOTIFICACAO_DESTINATARIO_SNAPSHOT : view_cliente_id
    OUTBOX_EVENTOS ||--o| DYNAMODB_DELIVERY_LEDGER : event_id
    ORDENS_SERVICO ||--o| DYNAMODB_DELIVERY_LEDGER : ordem_id
    USUARIOS {
        uuid id PK
        string email
        string role
    }
    CLIENTES {
        uuid id PK
        uuid usuario_id FK
        integer versao_identidade
        boolean ativo
    }
    VEICULOS {
        uuid id PK
        uuid cliente_id FK
    }
    ORDENS_SERVICO {
        uuid id PK
        uuid cliente_id FK
        uuid veiculo_id FK
        bigint versao
        bigint sequencia_historico
        string status
        timestamp criado_em_utc
    }
    OS_HISTORICO {
        uuid id PK
        uuid os_id FK
        uuid alterado_por FK
        uuid ator_cliente_id FK
        bigint sequencia
        timestamp ocorrido_em
        string status_novo
    }
    OUTBOX_EVENTOS {
        uuid event_id PK
        uuid os_id FK
        bigint sequencia FK
        string estado
        integer tentativas
        timestamp disponivel_em
    }
    OUTBOX_RECUPERACOES {
        uuid id PK
        uuid event_id FK
        string acao
        string motivo
    }
    NOTIFICACAO_DESTINATARIO_SNAPSHOT {
        uuid ordem_id
        uuid cliente_id
        boolean ativo
        integer versao_identidade
    }
    DYNAMODB_DELIVERY_LEDGER {
        string delivery_table
        uuid event_id
        uuid ordem_id
    }
```

`ordens_servico.versao` and `sequencia_historico` are the aggregate counters. `os_historico` stores the canonical timestamp/sequence only for verified new history; V6 allows legacy rows with both absent and constrains actor evidence. V7 adds `outbox_eventos`, with its composite foreign key to `(os_id, sequencia)` in `os_historico`, and `outbox_recuperacoes`, whose `event_id` is a real foreign key to the outbox. `notificacao_destinatario_snapshot` is a PostgreSQL **view**, not a delivery table: it joins the current order/customer contact state for the function's read-only lookup.

The function-owned `DELIVERY_TABLE` DynamoDB ledger is represented separately because it is not an APP PostgreSQL migration table. It claims/completes delivery by event/order and can suppress stale/retried FIFO messages. `outbox_eventos` uses the pending availability index; V8 adds canonical delivery and creation report indexes. Check constraints protect event type/schema/payload, state, attempt and recovery-code invariants; foreign keys retain customer, staff, history and outbox evidence.

[V6 migration](../../../src/main/resources/db/migration/V6__versionar_agregados_e_historico.sql) adds aggregate/history versioning, [V7](../../../src/main/resources/db/migration/V7__criar_outbox_e_destinatario.sql) adds outbox and delivery data, and [V8](../../../src/main/resources/db/migration/V8__indexar_relatorios.sql) provides report/outbox indexes. Operators use the [canonical reporting runbook](../../runbooks/canonical-reports.md) when totals disagree.
