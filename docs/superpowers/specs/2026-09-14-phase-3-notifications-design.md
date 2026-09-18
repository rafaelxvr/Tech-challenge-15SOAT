# Phase 3 — step 4B serverless notification delivery

Status: approved by the user ("approved"), including FIFO/Lambda/SES delivery, the residual duplicate window and stale-notification suppression. Steps 1–3, [4A data integrity](2026-09-14-phase-3-data-design.md) and [4C observability](2026-09-14-phase-3-observability-design.md) are also approved. This section completes the delivery behavior behind the approved transactional outbox. Parent: [Phase 3 specification](2026-09-14-phase-3-design.md).

## Approved notification decision

1. Publish committed order-status events from the application's PostgreSQL outbox to an environment-specific SQS FIFO queue.
2. Deliver status email through a small Lambda in `oficina-functions`, using SES and a read-only recipient lookup.
3. Preserve normal per-order processing order, record completed attempts in DynamoDB and suppress obsolete replays.
4. Retry transient failures; retain exhausted messages in a FIFO dead-letter queue with explicit operator recovery.
5. Allocate this work within the approved US$35 cloud-window allowance, including the shared SES and Lambda limits.

This addresses the assignment's serverless-notification objective without moving order transitions or inventory rules into a function. The notification service cannot approve an order, deduct stock or change business status. Receiving or opening an email grants no API access.

## Alternatives considered

| Approach | Benefit | Decision |
|---|---|---|
| Outbox → SQS FIFO → Lambda → SES | Durable handoff, ordinary per-order ordering and managed retry/dead-letter support | Recommended for the bounded study workload |
| Outbox → SQS Standard → Lambda → SES | Simpler queue semantics when order does not matter | Requires more application-level handling for reordered status messages |
| Outbox → direct Lambda invocation | One fewer AWS service | Requires a more bespoke retry/recovery contract across the publisher and invocation boundary |

No EventBridge bus, Kafka cluster, workflow orchestrator or extra repository is required for this path. FIFO does not make the complete email operation exactly once: SQS deduplication has a five-minute window, and Lambda queue processing can repeat records. [SQS deduplication window](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagededuplicationid-property.html), [Lambda queue processing](https://docs.aws.amazon.com/lambda/latest/dg/with-sqs.html)

## End-to-end flow and boundaries

```mermaid
sequenceDiagram
    participant API as Order application
    participant DB as PostgreSQL
    participant Relay as Outbox publisher
    participant Q as SQS FIFO
    participant F as Notification Lambda
    participant D as DynamoDB delivery records
    participant SES as Amazon SES
    API->>DB: Commit order, stock, history and event atomically
    API-->>API: Complete business response after commit
    Relay->>DB: Lock eligible pending event
    Relay->>Q: Send stable event ID and order group
    Q-->>Relay: Accepted by queue
    Relay->>DB: Mark published and commit
    Q->>F: Invoke with one event
    F->>D: Claim event / check prior sequence
    F->>DB: Read current recipient and identity version
    F->>SES: Send eligible status message
    SES-->>F: Accepted with MessageId
    F->>D: Record SES acceptance and sequence
    F-->>Q: Successful invocation; message acknowledged
```

Refine the existing `NotificacaoPort`, which currently takes a JPA `OrdemServico`, into a narrow port accepting an immutable notification-intent value. The application prepares that value from the committed-change candidate; the outbox adapter persists it in the same transaction as the aggregate. Do not serialize a JPA entity graph or use an after-commit callback as the only record of work.

Keep use-case rules, event validation and template selection in plain Java objects. Lambda/SQS, JDBC, DynamoDB and SES are input/output adapters. Share small function-side email and recipient-lookup components where their contracts match; authentication and status notification remain separate use cases. Do not duplicate order transition logic or create a general messaging framework for one event type.

The existing SMTP adapter remains useful as a local email transport, but local order operations should exercise outbox persistence too. A local publisher/handler harness can use MailHog and test adapters. That harness does not prove AWS FIFO, IAM or Lambda retry behavior; those require the planned cloud integration checks.

## Versioned event and recipient policy

Publish `StatusOrdemServicoRegistrado`, schema version 1, for the initial RECEBIDA history entry and each committed status transition, including estimate refusal returning to diagnosis. One event UUID is generated when intent is persisted and reused on every publication retry. The uniqueness rule from 4A prevents duplicate intents for one history sequence/event type.

| Event field group | Content | Rule |
|---|---|---|
| Identity | `eventId`, `eventType`, `schemaVersion` | Stable across retries; reject an unsupported schema version |
| Order/customer reference | `ordemId`, `numero`, `clienteId`, `versaoIdentidadeCliente` | Snapshot from the trusted application, not supplied by a public caller |
| Transition | `sequencia`, `statusAnterior`, `statusNovo`, `ocorridoEm` | Sequence identifies the history entry; UTC instant makes delayed messages understandable |
| Diagnostics | `correlationId`, W3C `traceparent` when valid | Preserve correlation across retries; never propagate unfiltered baggage or authentication headers |
| Excluded data | No CPF, email address, plate, OTP, JWT, arbitrary URL or free-form internal message | Use a bounded schema and a payload limit of 8 KiB |

Use `MessageGroupId = ordemId` and `MessageDeduplicationId = eventId`, with content-based deduplication disabled. Validate that the received group matches the event order. Queue access is restricted to the owning environment's publisher and consumer; the function has no public function URL.

Add an application-owned read-only view, `notificacao_destinatario_snapshot`, joining the order's customer relationship to the current customer record. Expose order/customer IDs, order number, active status, registered email and identity version. Include CPF and CNPJ customers; the CPF-only authentication view is not the correct contract for all business notifications. The dedicated database role may SELECT this view and cannot update customers or orders.

Before sending, compare the snapshot with the event's customer and identity version. Suppress the notification if the customer is missing/inactive or the version changed; record the reason. Do not send the queued message to an old address or silently redirect it to a newly registered address. New transitions use the new version. A contact change racing after the final lookup cannot recall a message already accepted by SES; the check is not a cross-system atomic transaction.

Use a fixed template containing order number, the historical status update and its event time. Refer the recipient to the authenticated workshop channel; do not embed bearer tokens, OTPs, shared status-update tokens or an invented public tracking UI. An API URL, if included, is an authenticated API reference and does not grant access merely by being clicked.

Keep login OTP email on the already-approved authentication flow. It is not delayed behind the order-status queue, and it retains its five-minute validity and generic public acknowledgement. Both flows still share the account's SES limits.

## Outbox publisher and normal ordering

Run one bounded publisher loop per application pod, initially polling every 5 seconds. Each iteration handles at most one event in a short, separate database transaction. Do not perform queue/network work inside the request's order transaction.

1. Select a due PENDING event with `FOR UPDATE SKIP LOCKED`. It is eligible only if no earlier unpublished or BLOCKED event exists for the same order; a later sequence must not overtake a locked predecessor.
2. Keep only that outbox-row lock while calling SQS, with an initial two-second SDK call deadline and one SDK attempt. Do not lock the order or stock rows during network publication.
3. After a successful SQS acknowledgement, mark the event PUBLISHED, record the queue message ID/time and commit. PUBLISHED means accepted by SQS, not emailed.
4. On a queue failure, keep the event pending and persist attempts, a safe error code and the next attempt time. Use exponential backoff with jitter, starting at 5 seconds and capped at 5 minutes; after 12 publication failures mark it BLOCKED and alert.
5. A BLOCKED predecessor stops only that order's later publication. Recovery retries the same event ID after fixing the cause, or records an explicit operator skip and reason. Never silently delete the blocked intent to make the queue appear healthy.

The row lock is deliberately held across one bounded queue call in the publisher transaction. This trades a short-lived database connection for simple publisher ownership, without a distributed lease service. Keep it inside the application's configured connection budget and measure contention. SQL/commit failure rolls back publication bookkeeping; retrying can publish the same event again. If database failure prevents saving the attempt count, back off locally and alert rather than spin. [PostgreSQL queue-style locking](https://www.postgresql.org/docs/16/sql-select.html)

This ordering query needs a real multi-connection integration test: `SKIP LOCKED` alone does not guarantee per-order sequencing. Publishing event N+1 becomes eligible only after event N's publication transaction commits. Order mutation and history remain governed by 4A's optimistic concurrency rules.

## Queue, worker and retry settings

| Component | Initial setting | Reason / limit |
|---|---|---|
| Source queue | One encrypted FIFO queue per environment; 4-day retention; 120-second visibility timeout | Durable backlog; normal processing groups by order |
| Dead-letter queue | One encrypted FIFO DLQ per environment; 14-day retention; `maxReceiveCount = 5` | Retain repeated failures for diagnosis and selected recovery |
| Lambda mapping | Batch size 1; maximum concurrency 2 per environment; no provisioned pollers | Simplifies failure acknowledgement; at most four notification invocations across both environments |
| Function | Initial 1 GiB memory, 20-second timeout; bounded JDBC/SES calls | Validate cold starts and memory in staging; no reserved or provisioned concurrency under the observed account limit |
| Business age and deduplication | Do not email events older than 24 hours; retain terminal delivery records/cursors for 30 days | Avoid stale status mail and preserve replay evidence beyond the normal retry period |

The 120-second visibility timeout follows AWS's minimum recommendation of six times the 20-second function timeout. With batch size 1, a transient failure fails the invocation; Lambda/SQS handles the retry. Do not add a second Lambda asynchronous-invocation DLQ for this event source. Actual retry timing depends on polling/backoff and is not an exact two-minute scheduler. [SQS mapping settings](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-configure.html)

The mapping concurrency limit is separate from function reserved concurrency. When all four notification slots are occupied, the observed regional Lambda quota of 10 leaves six for authentication, authorizers and other functions; those six are not reserved. Throttled notifications remain retryable. [FIFO concurrency limits](https://docs.aws.amazon.com/lambda/latest/dg/services-sqs-scaling.html)

SES sandbox permits 200 recipients per rolling 24 hours and a sending rate of one per second across both environments and both email flows. Use one recipient per message, verified demo identities, low-volume fixtures, and bounded jittered retries for explicit throttling. Mapping concurrency does not itself enforce the SES rate. Rehearsal must exercise a login-code request while status notifications are pending. Account-limit exhaustion triggers an alert and operator pause/review, not rapid retries or a paid-plan/sending-limit upgrade. [SES sending quotas](https://docs.aws.amazon.com/ses/latest/dg/manage-sending-quotas.html)

## Duplicate handling and delivery truth

Create a separate DynamoDB delivery table per environment, distinct from the five-minute login challenge data. Store an event record and an order-sequence cursor. Conditional writes claim work with an owner token and a 90-second lease; terminal completion updates the event and cursor atomically. Use strongly consistent point reads for decisions, prevent claims from overwriting terminal records, and never decrease the cursor when suppressing an older event. The function timeout is shorter than the lease, and completion writes must match its owner token. An unexpired lease held by another invocation is retryable, not a successful delivery.

| State/observation | Worker action | Recorded outcome |
|---|---|---|
| Event already terminal | Acknowledge without another SES call | Prior acceptance/suppression remains authoritative |
| Sequence older than or equal to a completed order cursor | Acknowledge without sending stale mail | SUPERSEDED |
| Missing/inactive recipient, changed identity version, or event older than 24 hours | Acknowledge after persisting the terminal reason | SUPPRESSED or EXPIRED; visible in reporting |
| SES accepts the message | Persist its MessageId and advance the order cursor before acknowledging | SES_ACCEPTED |
| Temporary dependency/SES failure, malformed event or unsupported schema | Fail the invocation; record safe diagnostics where possible | Retry, then DLQ if the receive limit is exhausted |

Do not acknowledge a message when a required delivery-record write failed. Check age/lease expiry in code; DynamoDB TTL is eventual cleanup and cannot act as the correctness mechanism. Keep order cursors while their associated replay evidence remains valid. [DynamoDB TTL behavior](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html)

If SES accepts a message but the worker crashes or cannot persist SES_ACCEPTED, a retry after lease expiry can send a duplicate. SES and DynamoDB cannot be committed atomically. This design favors retryable notification delivery, tolerates that residual duplicate window, and never repeats a business mutation. Similarly, an ambiguous network timeout during SendEmail must not be described as proof that no email was sent. [SES send response](https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html)

SES_ACCEPTED means accepted for sending. It does not prove arrival in the recipient's inbox, and this version does not add bounce/complaint delivery-receipt infrastructure. Demonstration evidence includes an actual received email separately from the acceptance log.

## Dead-letter recovery and stale messages

Moving a failed FIFO message to a DLQ allows later messages in that order's group to proceed. Therefore, exact order is not preserved across dead-letter extraction/replay. The sequence cursor suppresses an older recovered event if a newer notification has already reached a terminal state. This is a status-notification policy; the queue never carries commands that would replay order transitions. [FIFO/DLQ ordering limitation](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html)

1. Inspect the selected event's schema, safe error, event time, current contact version and latest completed sequence. Distinguish a publisher BLOCKED row from a consumer DLQ message.
2. Correct the dependency, permissions, template or code issue and verify it with a test event before replay.
3. Replay only selected still-relevant events with their original event UUID; preserve application deduplication identity even if SQS assigns a new transport ID. Do not blindly move the whole DLQ back.
4. Suppress expired/contact-changed/superseded events with an auditable reason. If a new current-status summary is needed, design/request that explicit operation; do not edit historical payloads or invent a new event ID to bypass deduplication.
5. Verify the outcome and queue backlog. Replaying can send another external email; it is an operator action, not an automatic infinite recovery loop.

Retain published outbox rows for 7 days after publication and delivery evidence for 30 days, subject to the approved cloud lifecycle. Pending/BLOCKED rows are not silently purged. Queue retention still limits how long failed messages remain, so export unresolved cases and evidence before teardown or account expiry. No existing records are deleted by this design review.

## Observability contract for step 4C

| Signal | Required interpretation / initial alert proposal |
|---|---|
| Pending/BLOCKED outbox count and oldest age | Alert on any BLOCKED event or pending age above 60 seconds for two consecutive samples |
| SQS backlog/age and DLQ visible count | Alert on a nonempty DLQ; queue age above 5 minutes indicates delayed delivery |
| Notification outcome counters | Separate SES_ACCEPTED, duplicate, suppressed, expired, superseded and failed attempts |
| Delivery dependencies and saturation | SES throttles, Lambda throttles/errors, lookup failures and delivery-ledger failures; no false success on a dependency outage |
| Correlated JSON logs and traces | Follow order request → event → publisher → worker → SES acceptance with event ID, order ID, environment and trace context; omit contact data and tokens |

Event/order IDs belong in diagnostic records, not unbounded metric labels. Use bounded labels such as environment, event type, outcome and safe failure code. A rolled-back order-processing failure has no outbox event; its application error/metric must still be observable. Dashboards must distinguish business transaction failure from asynchronous notification failure. Approved step 4C defines tooling, sampling, retention and alert delivery.

## Ownership, cost and acceptance

| Repository | Responsibility |
|---|---|
| `oficina-app` | Outbox migration/adapter/publisher, recipient view and database-role bootstrap, event schema fixtures, publish permission and queue reference |
| `oficina-functions` | Notification use case/handler, SES adapter, delivery table, source FIFO/DLQ, mapping, retry settings and environment-scoped consumer IAM |
| `oficina-db-infra` | Existing RDS infrastructure/security/backup foundation; no duplicate application schema ownership |
| `oficina-k8s-infra` | Existing network, workload identity plumbing, telemetry foundation and shared platform capacity |

The application bootstrap creates the notification view/role and secret reference before the function consumer is enabled. Functions consume the exported references; the application publisher consumes the function repository's queue output. Keep schema/bootstrap and publisher rollout as separate phases so those dependencies do not form a creation cycle. The consumer can read only its recipient view, delivery table and queue; it has no database mutation rights.

Use AWS-owned/server-side encryption and existing private-network egress for this study profile; no new NAT gateway, load balancer, customer-managed KMS key or always-running worker is needed. The extra function remains in `oficina-functions`, keeping the required four-repository structure.

For a 48-hour rehearsal, allocate **US$0.15 of the existing US$2.50 miscellaneous allowance** to SQS/delivery-state/email usage; this is an allocation inside the approved estimate, not an increase. A planning envelope of 200,000 FIFO request units (including polling/retries) costs US$0.10 at US$0.50/million; 200 small email recipients cost about US$0.02 at US$0.10/thousand; the remaining allowance covers small delivery-table storage/read/write usage. Keep the combined OTP/status-email fixture total within the already approved 200-email window assumption. [SQS pricing](https://aws.amazon.com/sqs/pricing/), [SES pricing](https://aws.amazon.com/ses/pricing/), [DynamoDB pricing](https://aws.amazon.com/dynamodb/pricing/)

The SQS rate was checked using the read-only AWS price catalogue on 2026-09-14, `us-east-1`, FIFO SKU `624ZQ7K46KHFAD9Y`; the ordinary SES Message SKU is `CHVKAHDAUUYM9EJ2`. Do not confuse it with another SES message-processing product. At most 1,000 notification attempts at the proposed 1 GiB/20-second timeout would consume 20,000 GB-seconds, inside the earlier 200,000-GB-second all-function allowance. Measure real usage, including idle queue polling, retries and retained evidence; revise the total if it exceeds the allocated envelope. The US$35 window allowance and US$20 separate project reserve remain unchanged.

TDD and cloud acceptance:

1. A committed transition creates one stable intent; rollback creates none. With two real PostgreSQL publisher connections, a locked/retrying predecessor cannot be overtaken by the same order's next event.
2. Queue publication timeout and post-publication database failure retain retryable intent with the same event ID; already-completed delivery and stale-sequence replay do not invoke SES again.
3. Recipient deactivation/version change, expired events, invalid schemas and dependency failures produce the specified outcomes without touching order/stock state or disclosing contact data in logs.
4. Inject worker failure after SES acceptance and before ledger completion; demonstrate the documented duplicate possibility rather than claiming exactly-once delivery. Verify retries, five-receive DLQ routing and selected recovery with actual AWS behavior during the approved window.
5. Demonstrate a status email, an OTP login while notifications are pending, correlated request/event logs and a controlled notification-failure alert. Verify both environments' limits and the credit allocation.

Next action (under 1 minute): open the [implementation plan](../plans/2026-09-15-phase-3-implementation.md) and choose subagent-driven or inline execution. All five design steps are approved; implementation has not started.
