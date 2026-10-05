# ADR 006 — Orchestrated saga for the service-order lifecycle

## Status

Accepted — Phase 4.

## Context

Phase 4 splits the monolith into os-service, billing-service (orçamento, Mercado Pago payment) and
execution-service (diagnosis and repair queue). Opening an order, generating the orçamento, the customer
decision, payment, stock reservation and repair now span three databases; no distributed transaction is
available or wanted.

## Decision

Use an **orchestrated** saga owned by os-service (`OsSaga`, table `os_saga`), not choreography.

- The aggregate decides every transition and records effects: commands for other services and local
  actions on `OrdemServico`/`Peca`. Effects, the saga row and its step log commit in one transaction.
- Commands go through a dedicated `saga_outbox` (per-OS ordering, `FOR UPDATE SKIP LOCKED`, exponential
  backoff, `BLOCKED` after 12 attempts) to SQS FIFO queues owned by each consumer
  (`billing-commands.fifo`, `execution-commands.fifo`). Replies arrive on `os-saga-replies.fifo`.
- Replies are processed once (`saga_inbox`) and accepted only when `causationId` equals the command the
  saga awaits; late or duplicate replies have no effect. A failed reply holds back later replies of the
  same FIFO group in the same receive batch (messages without a group share one key), preserving order.
- Compensations run in reverse order from a persisted stack: cancel execution job, restore stock (local),
  refund payment, cancel orçamento, then cancel the order. Starting the repair is the pivot: nothing after
  it is compensated.
- Every wait has a deadline (`prazo`); a poller turns expired waits into failures and re-sends unconfirmed
  compensations, up to 5 sends in total (1 + 4 re-sends), before `FALHA_COMPENSACAO` (alerted, resolved by
  runbook). Each due saga is
  processed in its own transaction: a failing saga is logged and skipped, it does not block the others.
- Pollers contain and log every tick failure (`event_name=poller_falhou`) and keep running.

## Why orchestration

- One place holds state, current step, deadlines and compensation log — failure handling is readable,
  testable and demonstrable (`GET /ordens-servico/{id}/saga`).
- The lifecycle already lived in os-service; the orchestrator sits beside the aggregate it drives.
- Reuses the proven Phase 3 outbox design and SQS FIFO; no new broker.
- Choreography was rejected: compensation logic would spread over three repositories and event cycles
  are easy to introduce. RabbitMQ/Amazon MQ was rejected: new infrastructure, no outbox reuse, extra cost.

## Consequences

- os-service knows the step order (accepted orchestrator coupling).
- Contracts are versioned JSON Schemas (`contracts/<type>/v<N>.json`) owned by the consuming service.
- Orders created before this change (no saga row) keep the manual Phase 3 flow.
- Database grants: the release bootstrap (pinned to Flyway V8) skips only the four post-V8 saga tables
  (`os_saga`, `os_saga_passo`, `saga_outbox`, `saga_inbox`) when absent; runtime grants for those tables
  are delivered by the platform rollout (Phase 4 Plan 4) before messaging is enabled.
