# Phase 3 — step 4C observability

Status: approved by the user ("approved"), including New Relic Free, the minimal public health route, SQL snapshot dashboards and the native AWS alert split within the existing budget. Steps 1–3, [4A data integrity](2026-09-14-phase-3-data-design.md) and [4B notifications](2026-09-14-phase-3-notifications-design.md) are also approved. Parent: [Phase 3 specification](2026-09-14-phase-3-design.md). This document defines design and acceptance checks; no agents, accounts, alerts or cloud resources have been installed or created by this review.

## Approved observability decision

1. Use New Relic's permanent Free plan for application/Kubernetes monitoring, correlated logs, sampled traces and business dashboards.
2. Use Spring Actuator's separate startup, liveness and readiness checks; expose one minimal public health route for external uptime monitoring.
3. Keep PostgreSQL as the source of business counts and status-duration reports; export aggregate snapshots every 60 seconds.
4. Alert through New Relic for application failures and through native CloudWatch for queue backlogs and Lambda throttling, including failures before a handler starts.
5. Keep the approved US$35 cloud-window allowance, its US$3 telemetry allocation and the existing Kubernetes/Lambda capacity limits. Verify actual overhead before the cloud demonstration.

## Provider and cost boundary

New Relic currently advertises a permanent Free plan with 100 GB monthly ingestion, one full-platform user, unlimited basic users and no credit card requirement. Its published Free-plan description says ingestion stops at the allowance instead of continuing until the user upgrades. Verify the actual account plan and remaining shared allowance before connecting this project; a paid account's free ingestion allowance is not the same billing boundary. Use the US data region and no paid upgrades, extended retention, advanced compute or paid tracing add-ons. [Free plan and limits](https://newrelic.com/info/pricing), [pricing](https://newrelic.com/pricing)

The one full-platform user is the project operator; additional team members use only access included in the Free plan. A recording and exported evidence must remain sufficient for the assignment reviewer without requiring a paid seat or publishing telemetry publicly. Account creation and invitations are later user actions.

| Option | Assessment |
|---|---|
| New Relic Free | Recommended: hosted storage and dashboards preserve the small worker-node budget; integrates the capabilities named in the assignment |
| Self-hosted Prometheus, Grafana and log/trace storage | Viable alternative, but adds storage, resource sizing and operational work to the two-node study environment |
| A paid plan or temporary paid-feature trial | Outside the approved spending constraint; a trial must not become a dependency for completion |

The AWS account remains FREE with the previously verified credits. New Relic's free service does not make AWS logs, network traffic, function runtime or alarm delivery free. The cloud window remains at most 48 elapsed hours, including setup and cleanup.

## Collection paths and engineering boundaries

| Signal | Collection path | Responsibility |
|---|---|---|
| API/JVM/SQL performance and request traces | Pinned New Relic Java agent in the application image; HTTPS export through existing NAT | `oficina-app`; no telemetry agent API in domain entities/value objects |
| Kubernetes CPU, memory, replicas, HPA, restarts and scheduling | Minimal pinned `nri-bundle`: infrastructure/Kubelet collectors, kube-state-metrics and its scraper | `oficina-k8s-infra`; one cluster installation with environment labels |
| Application JSON logs | stdout → New Relic Kubernetes log forwarder → New Relic | Disable Java-agent log forwarding to avoid a second copy in New Relic |
| Java Lambda telemetry and JSON logs | Pinned Java 17 slim agent layer with its bundled extension; direct HTTPS export | `oficina-functions`; extension is the sole New Relic function-log sender |
| AWS queue/throttling signals | Native CloudWatch metrics, dashboards and alarms → SNS email subscription for the operator | Queue/function repository owns its alarms; platform owns environment alert topics |

Start with low-data mode, a 60-second Kubernetes collection target and only the listed collectors. Disable Pixie/eBPF, auto-attachment operators, duplicate Prometheus scraping, verbose integration logs and collection of inaccessible EKS control-plane components. Helm rendering must verify the actual supported settings for the pinned chart. The existing metrics-server remains the HPA's resource-metric source; New Relic is not in the scaling control loop. [Kubernetes collection components](https://docs.newrelic.com/docs/kubernetes-pixie/kubernetes-integration/get-started/kubernetes-components/), [reducing ingestion](https://docs.newrelic.com/docs/kubernetes-pixie/kubernetes-integration/installation/reduce-ingest/)

Use the current Java agent's serverless mode, not a legacy Java 8/11 wrapper. The slim layer instruments Lambda handlers; explicitly enable only the needed JDBC/HTTP/AWS SDK modules or focused adapter spans. Do not assume it traces every dependency by default. The vendor documents several seconds of additional cold-start overhead, so measure challenge, verification and authorizer paths as well as the notification handler. Preserve the approved 10 shared Lambda concurrency limit and notification function's 1 GiB/20-second configuration; do not solve overhead by silently enabling provisioned concurrency or enlarging the budget. [Java serverless instrumentation and limitations](https://docs.newrelic.com/docs/apm/agents/java-agent/getting-started/java-agent-approaches-lambda/)

There is no CloudWatch-to-New-Relic forwarding Lambda, Firehose stream or account-wide AWS polling integration in this initial design. Native AWS queue/throttle graphs remain in a small linked CloudWatch dashboard; New Relic shows application-visible dependency outcomes. This avoids missing pre-handler throttles while keeping collection paths explicit.

Use small observability adapters around HTTP/function/use-case boundaries. Domain rules, aggregate transitions, actor rules and business timestamps remain independent of the vendor. Use SLF4J/Logback and a maintained Java-17-compatible JSON encoder for the Spring Boot 3.2.5 application; do not assume a structured-logging feature introduced in a newer Boot release is available. Pin dependency/chart/layer versions and checksums during implementation. The function package uses the same JSON field contract without copying the application's JPA model or creating a shared framework repository.

## Health and availability

The current `k8s/app.yaml` points liveness and readiness at the same `/api/actuator/health` endpoint. Separate them so a database outage removes unhealthy targets without repeatedly restarting healthy JVMs. Spring Boot 3.2.5 supports health groups and Kubernetes probes; explicitly include the DB check in readiness. [Spring Boot 3.2.5 probes](https://docs.spring.io/spring-boot/docs/3.2.5/reference/html/actuator.html#actuator.endpoints.kubernetes-probes)

| Check | Proposed behavior |
|---|---|
| Startup | `/api/actuator/health/liveness`, every 5 seconds, up to 120 seconds; liveness begins after startup succeeds |
| Liveness | `/api/actuator/health/liveness`, every 10 seconds, 3 consecutive failures; process/application availability only |
| Readiness and ALB target health | `/api/actuator/health/readiness`; ready state plus bounded PostgreSQL check; Kubernetes every 10 seconds, 3 failures; configure/test ALB thresholds separately |
| External uptime | New Relic simple ping, one location, every minute per environment, against `GET /health` through the public API Gateway |

`GET /health` is a proposed addition to the explicit public-route list. Each HTTP API rewrites this exact route to the internal readiness path, with only an UP/DOWN body and HTTP 200/503. It does not disclose dependencies, credentials, build details or metrics. No wildcard Actuator route is added. Internal probe paths also return status only; metrics/info remain restricted and are not exposed through the public gateway. Check gateway rewriting, Spring Security and the actual servlet context path together.

A required DB outage makes the application unready. SES, SQS or telemetry exporter outages do not fail application liveness/readiness: committed work can wait in the outbox, and monitoring failures must not block orders. External uptime tests API Gateway → private integration → application → DB, not successful CPF login; authenticated smoke tests demonstrate that separately.

Simple ping monitors are exempt from New Relic's published 500 monthly non-ping-check allowance. Two one-minute pings generate approximately 5,760 HTTP API requests in 48 hours, inside the existing 100,000-request envelope. Disable the monitors when the cloud window ends. Do not add scripted login checks that send OTP email on each interval. [Synthetic check limits](https://docs.newrelic.com/docs/synthetics/synthetic-monitoring/getting-started/monitor-limits/)

## JSON logs and correlation

Use UTC ISO-8601 timestamps and a fixed schema: `timestamp`, `level`, `service`, `environment`, `version`, `event_name`, `message`, `correlation_id`, `trace.id`, `span.id`, and optional `error_code`. Add `event_id`, `order_id`, `history_sequence` or provider request ID only when needed for diagnosis. These IDs are searchable log/trace fields, never custom metric dimensions. No customer identifier is needed for routine operational metrics.

Generate or validate a bounded correlation ID at ingress, return it on application responses, and propagate it through the outbox and notification event. Preserve validated W3C trace context across HTTP and capture context at the application boundary before committing the outbox. When the asynchronous publisher runs, create a producer span from the stored context; inject its context into the outgoing event, and extract it in the Lambda consumer adapter. Retain the business correlation/event ID even when the trace is unsampled or the delivery occurs much later. Do not persist a live agent transaction object or thread-local state in an aggregate. Clear MDC in `finally`, including pooled and reused Lambda threads.

Gateway access logs use the managed request ID, route key, status, latency and safe error category. Forward the gateway-generated ID to the backend through controlled integration mapping so gateway-only errors can be located. Authorizer and backend spans are not assumed to form one automatic parent/child trace; test the actual propagation and use the common gateway request ID where only correlation is available. No fabricated gateway span or complete-trace claim.

Do not log CPF/CNPJ, names, email, plate, OTP, passwords, JWTs, Authorization/Cookie headers, signing keys, request bodies, query strings or arbitrary exception messages containing user/SQL values. Use route templates such as `/ordens-servico/{numero}`, not raw paths. Disable Hibernate SQL/bind logging in cloud profiles. Configure agent attributes, SQL capture and log forwarding to the same allowlist; sanitizing only application log statements is insufficient. Permit controlled stack frames/error codes while removing sensitive exception text. The existing SMTP adapter's contact-address logging must be removed when replacing it.

Keep operational WARN/ERROR events without deliberate source sampling, suppress repetitive health/access INFO logs, and bound exporter memory/retries. Delivery can still be lost during an exporter outage or overload; JSON stdout plus short-lived CloudWatch function/gateway logs provide limited fallback, not a durable audit ledger. Business history and outbox remain the durable records. Sampling, dropped telemetry and stale exports must be visible.

Enable standard sampled distributed tracing, with an initial cap of 500 stored span events per application-agent harvest and the supported bounded Lambda equivalent. Leave paid Infinite Tracing off. Confirm the pinned agents' effective sampling configuration and limits; do not promise every error has a retained trace. Request counts/aggregate timing and explicit failure counters must not depend on retained trace samples. Use low-volume demonstration requests and verify a complete selected trace before recording. [Java tracing](https://docs.newrelic.com/docs/apm/agents/java-agent/configuration/distributed-tracing-java-agent/), [sampling behavior](https://docs.newrelic.com/docs/distributed-tracing/concepts/how-new-relic-distributed-tracing-works/)

## Business reporting without duplicate counts

Keep the step 4A formulas unchanged: business day `America/Sao_Paulo`, UTC half-open query boundaries, canonical order-creation time and completed histories. For orders delivered in a selected period, total all completed intervals per order/status before averaging across eligible orders. Finalization measures the wait from FINALIZADA to ENTREGUE. Current status age is separate; empty samples are N/A, and incomplete histories are counted as excluded.

The ADMIN reporting endpoint accepts explicit date boundaries and returns aggregate DTOs. The observability adapter uses the same query implementation directly, not a public admin HTTP call or aggregate rehydration. Every 60 seconds, with jitter, each running application replica exports aggregate `WorkshopReportSnapshot` events for the current and six previous business dates plus one rolling-seven-business-day window. It also exports outbox pending/BLOCKED counts and oldest pending age. Queries use a bounded read-only consistent transaction, the existing Hikari pool and a 2-second statement limit; failure skips export and emits an operational failure signal. Measure the query at maximum replica count before accepting this cadence.

Each period snapshot contains `environment`, `window_kind`, `period_start`, `period_end`, `business_date` where applicable, `captured_at`, `created_count`, eligible/excluded counts and each status's total seconds, sample count and mean. Daily/rolling periods are half-open business-date intervals, with the current day necessarily partial. Release the database transaction/connection before network export. Stamp telemetry with capture time and verify that a delayed older export cannot replace a newer snapshot merely by arriving later. No per-customer or per-order payload enters these snapshots. Reuse the aggregate query result; do not multiply repeated snapshots into business totals.

For dashboards, select the **latest snapshot for each environment and exact period**, with a maximum freshness of 3 minutes. Multiple replicas and retries may export equivalent snapshots; use latest values, never `count(*)`, `sum(created_count)` or an average of repeated means. Rolling-period means come from the corresponding SQL aggregate, not an average of daily means. Always export numeric total-seconds and sample-count fields, including zero; display their latest-value quotient, with a zero denominator yielding N/A. Do not let a missing/null mean select an older nonempty snapshot. Missing or stale exports display no data/stale, not zero. This intentionally permits harmless duplicate telemetry and avoids adding a distributed leader or another durable reporting queue.

The same adapter emits a `WorkshopStatusSnapshot` per current status/environment with the current order count, maximum known age, valid-age sample count and unknown-age count. Age uses the latest canonical transition instant, not unverified legacy local timestamps. Select the latest status snapshot with the same freshness policy. These are current-work gauges, separate from the delivered-order duration cohorts; they contain no order identifiers.

The dashboard has explicit Today/last-seven-business-days panels and date labels. Its ordinary telemetry time picker does not recompute the SQL cohort for arbitrary dates; arbitrary reporting ranges use the ADMIN API. Acceptance must verify the implementation of latest-period selection and freshness, including two replicas, midnight rollover and a stopped exporter. The PDF's daily count and per-status duration requirements are covered without pretending a sampled trace count is a business ledger.

## Dashboards and operational events

| Dashboard | Required panels and interpretation |
|---|---|
| API and availability | Request rate, aggregate response time, estimated p95 from available APM data with sampling disclosed, 4xx/5xx by route template, uptime results, readiness failures and JVM/pool saturation; auth cold starts separated from warm requests |
| Kubernetes | Node/pod CPU and memory, requests/limits, HPA desired/current replicas, unavailable pods, restarts/OOM and pending workloads, with environment filtering |
| Workshop operations | Daily created orders for seven business dates; Today/seven-day delivered-cohort means for Diagnosis, Execution and Finalization wait; sample/excluded counts; current-status aging; capture time/staleness |
| Integrations and delivery | App-observed PostgreSQL/SQS/SES failures, OTP/notification outcomes, oldest outbox age and BLOCKED count, correlated error logs; link to native CloudWatch queue age, DLQ depth and Lambda throttles |

Record low-cardinality order-command outcome counters at the use-case/HTTP boundary: accepted, business-rejected, conflict and technical-failure, with operation and environment dimensions. Emit technical failure after the transaction has failed; rolled-back work has no outbox event but still needs an alert. Expected 401/403/404/409/422 responses do not count as technical order-processing failures. An external retry is another operational attempt, not another created order.

Notification logs/counters use the approved terminal states and categories: SES_ACCEPTED, duplicate, SUPPRESSED, EXPIRED, SUPERSEDED, retryable failure and DLQ exhaustion. SES_ACCEPTED is not proof of inbox delivery. Never count raw SES calls or Lambda retries as unique completed orders. Lambda throttling may produce no handler log, which is why native AWS metrics are included.

## Initial alert policy and recovery

These are demonstration thresholds, not a promised production SLA. Tune only after measured baseline results and keep the final values in version control. New Relic conditions deliver to the operator's verified email; CloudWatch alarms use an environment SNS topic with a confirmed email subscription. No automatic queue replay, business mutation, paid escalation or destructive remediation is attached.

| Group | Initial condition | Operator response |
|---|---|---|
| Service and order failures | Any technical order-processing failure in a 1-minute window; external ping failure sustained for 2 minutes; app 5xx ratio above 5% for 5 minutes with at least 20 requests | Inspect error code/correlation, DB reachability and release; distinguish business rejection from incident |
| Outbox and queue | Any BLOCKED outbox item; oldest pending over 60 seconds for 2 consecutive 60-second samples; native source-queue oldest age over 300 seconds for 2 one-minute periods; either DLQ visible depth above zero in 1 period | Restore dependency, then use step 4B's inspected retry/replay process; no blind bulk redrive |
| Capacity and function health | Container memory over 85% of limit for 5 minutes, node CPU over 85% for 5 minutes, pending/unavailable app pods for 2 minutes; native Lambda Throttles above zero in 1 minute per function | Check HPA/allocatable capacity and the shared quota; lower test load before considering any design change |
| Integrations and telemetry | SES send/DB lookup/DynamoDB delivery-state failure; missing application/reporting heartbeat over 3 minutes during an active window; exporter drops; telemetry usage at 50% and 80% of the window allowance | Diagnose provider error and freshness; reduce collection/load within policy and preserve evidence |

Stateful native SQS/Lambda alarms use exact resource dimensions and standard one-minute metrics, not metrics inferred from message-processing logs. A DLQ alarm remains active while messages remain; no-data is not automatically a healthy reading. Missing AWS metrics and stopped applications must be interpreted alongside the cloud-window state, source health and heartbeat loss. Real notification arrival includes collection/evaluation/delivery latency; do not claim a strict 60-second incident notification guarantee.

Mute environment operational alerts and disable pings only after the planned window's close has been recorded. An unexpected outage while the window is active remains actionable. Recovery and evidence capture are part of each alert's runbook. Testing alert delivery uses the explicitly configured operator address during the later authorized deployment/demo work.

## Retention, credentials and resource envelope

| Item | Limit or rule |
|---|---|
| New Relic retention | Free/default retention only, which varies by data type; record actual account values before rehearsal, export evidence before expiry, purchase no extension |
| AWS log retention | 1 day for Lambda and API Gateway log groups; no second CloudWatch copy of Kubernetes application logs; allowlisted gateway access logs only |
| Ingestion and AWS telemetry allocation | Up to 3 GB combined New Relic + CloudWatch ingested data for the entire window, with CloudWatch portion at most 1 GB; keep all telemetry network bytes inside the previously approved NAT/transfer assumptions |
| Kubernetes requests | Aim for telemetry at most 650m CPU / 1.5 GiB combined; all system add-ons plus telemetry must still fit 1.25 CPU / 3 GiB. Application agent/encoder buffers stay inside the app's 768 MiB request / 1 GiB limit |
| Lambda and secrets | Include agents/extensions, cold starts and export duration in the existing 200,000 GB-second envelope. Use two environment ingest-key secrets inside the earlier 16-secret allowance; recount the full inventory |

These resource figures are planning ceilings, not evidence that chart defaults fit. Count every sidecar/DaemonSet on both workers, system reservations, pod/ENI limits and rollout overlap. Render the charts, compare effective requests, measure 30 minutes of representative traffic and inspect JVM/function memory and cold starts. If the stack exceeds the envelope, reduce optional collectors or revise the design before cloud acceptance. Do not inflate resource requests or understate memory use to make the arithmetic appear to pass.

Allocate the existing US$3 telemetry allowance as planning provisions: US$0.60 for at most 1 GB of ordinary CloudWatch log ingestion plus its short storage; US$0.30 for at most 20 standard metric alarms during the 48-hour window; US$0.40 for bounded dashboard/API/query/SNS usage; US$1.70 for measurement uncertainty. Do not enable custom high-resolution CloudWatch metrics, Container Insights, continuous Logs Insights queries or paid New Relic add-ons. Reprice the actual region/rates and projected usage before apply; these allocations are not a hard AWS spending cap. All usage contributes to the existing US$35 window allowance and US$80 project allowance, preserving the separate US$20 reserve. [CloudWatch pricing](https://aws.amazon.com/cloudwatch/pricing/), [SNS pricing](https://aws.amazon.com/sns/pricing/)

The 3 GB operational threshold is enforced through measured usage, bounded traffic, source filtering and collection controls; it is not a provider-enforced 3 GB quota. At 80%, stop discretionary load and reduce nonessential collection; before exhausting it, close the rehearsal/export evidence and disable the affected exporters. Allow for metering delay and in-flight data. Never interpret a delayed usage graph as permission to spend the remaining project reserve.

Runtime ingest keys are separate per environment and distinct from the New Relic user/API key used by Terraform. Keep ingest values out of Terraform variables/state by referencing secrets whose values are supplied securely outside Terraform. Application/collector workloads receive only their ingest key; CI configuration credentials never enter pods/functions. The Lambda extension can retrieve an explicitly named regional Secrets Manager secret; give its role only that secret permission and verify the pinned Java layer works with this route. Do not copy a console example's plaintext key into Lambda configuration or repository files. [Lambda secret configuration](https://docs.newrelic.com/docs/serverless-function-monitoring/aws-lambda-monitoring/instrument-lambda-function/env-variables-lambda/)

Use the existing NAT for TLS export, minimal collection RBAC and read-only host log mounts where required. No extra inbound internet listener, public Kubernetes management endpoint, fifth repository or customer data export is introduced. Synthetic fixtures remain the cloud demonstration dataset.

## Ownership and acceptance

`oficina-k8s-infra` owns cluster collectors, shared environment labels, New Relic Terraform dashboard/alert/synthetic resources in a separate state root, native CloudWatch dashboard composition and environment SNS topics. It consumes references exported by the other repositories. `oficina-functions` owns its instrumentation, function/queue log settings and native alarms. `oficina-app` owns log/event schemas, probes, reporting DTOs/queries, business failure instrumentation and the semantic dashboard-query definitions consumed by the platform root. `oficina-db-infra` supplies DB identity/dimensions and DB-specific operational settings. Every Terraform resource has one owner; platform monitoring can be applied after workload outputs exist without creating an infrastructure bootstrap cycle.

Implementation acceptance requires:

1. **Privacy and correlation:** parse real JSON output; assert sensitive fixture values/headers are absent from logs, spans and agent payloads. Trace an authenticated order action through commit, publisher and notification, including a retry, without confusing a diagnostic ID with authorization.
2. **Health behavior:** remove DB connectivity and observe readiness fail while liveness stays UP; recover without restart storms. Confirm only minimal public `/health` works, protected/Actuator routes retain their policy, and pings reach the actual gateway path.
3. **Reporting correctness:** use step 4A's 30/50/20-minute fixture; verify re-entry, zero samples, incomplete history, time-zone boundaries, two replicas and stale exports. Counts/means must equal the SQL reporting API and must not grow merely because another snapshot arrives.
4. **Failure and recovery:** demonstrate technical order failure with rollback, outbox lag/BLOCKED, retry/DLQ, source throttling and email delivery to the operator; verify recovery. Routine business rejection must not trigger a technical-failure alert.
5. **Capacity and evidence:** measure agent overhead/cold starts, report-query time, collector resources and actual ingestion/cost projections inside the approved envelope; save dashboard, trace, log and alert/recovery evidence before cleanup. Local/CI tests use fakes/captured exporter payloads and Kind where needed, with no New Relic secret required for ordinary test runs.

Next action (under 1 minute): review [step 5's final acceptance and documentation decisions](2026-09-14-phase-3-acceptance-design.md). Step 4C is approved.
