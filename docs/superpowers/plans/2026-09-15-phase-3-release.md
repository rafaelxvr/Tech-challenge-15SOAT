# Phase 3 Observability and Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce trustworthy monitoring, deploy the reviewed release inside the approved AWS window and assemble the required submission evidence.

**Architecture:** Vendor adapters instrument boundaries while PostgreSQL owns business-report truth. New Relic handles app/Kubernetes/business telemetry; native AWS alarms cover queue and pre-handler failures. Release/submission records identify actual tested revisions and outcomes.

**Tech Stack:** Spring Actuator, JSON Logback encoder, New Relic Java/slim Lambda agents and Kubernetes bundle, HTTP Event API, Terraform New Relic/AWS providers, CloudWatch/SNS, Markdown/Mermaid and a local PDF renderer.

**Spec:** [Observability](../specs/2026-09-14-phase-3-observability-design.md), [acceptance](../specs/2026-09-14-phase-3-acceptance-design.md), [execution index](2026-09-15-phase-3-implementation.md).

## Global Constraints

New Relic Free, no paid features; telemetry allocation US$3; at most 3 GB combined New Relic/CloudWatch ingestion with at most 1 GB CloudWatch. All add-ons plus telemetry requests stay within 1.25 CPU/3 GiB pending actual measurement. App request 250m/768Mi, limit 1CPU/1GiB. Snapshots every 60 seconds and stale after 3 minutes. Video target 14 minutes, maximum 15 minutes. No destructive cleanup, publishing, invitation or portal submission without its concrete authorization.

## File map

R1/R2 add APP `adapter/out/observability`, `application/observability`, `config` and FUN `observability` adapters. K8S owns the New Relic `infra/monitoring` state, chart values and shared dashboards/topics; FUN owns its resource-dimensioned AWS alarms. APP hosts the documentation/evidence index and submission scripts. Other repositories keep their specific README/diagram details and link to that index.

### R1: Instrument privacy-safe logs, trace boundaries and probes

**Files:** APP Create `src/main/resources/logback-spring.xml`, `src/main/java/com/oficina/config/CorrelationFilter.java`, `src/main/java/com/oficina/adapter/out/observability/NewRelicOrderTelemetry.java`, `src/main/java/com/oficina/application/observability/OrderTelemetry.java`; Modify `application.yml`, cloud profiles, HTTP/use-case exception boundaries and Dockerfile. FUN Create `observability/JsonLogger.java`, `observability/TraceContextAdapter.java`; Modify handlers. Test APP `config/ObservabilityContractTest.java`, `config/HealthGroupsTest.java`; FUN `observability/TelemetryPrivacyTest.java`.

**Interfaces:** `OrderTelemetry.commandCompleted(String operation,String outcome): void` uses bounded operation/outcome values; outcome accepted/business-rejected/conflict/technical-failure. `TraceContextAdapter` extracts/injects only validated W3C context, not authentication headers or arbitrary baggage. CorrelationFilter accepts a bounded valid ID or generates UUID, returns it and clears MDC in finally.

- [ ] **1 — Red privacy test.** Capture real encoder/handler output with fixture secrets and parse each line as JSON. Assert tokens, CPF, email/plate, query/body text and sensitive exception messages are absent; required correlation fields exist.

```java
JsonNode log = mapper.readTree(capturedLine);
assertThat(log.path("correlation_id").asText()).isEqualTo(correlationId);
assertThat(capturedLine).doesNotContain(bearerToken, "123456", "39053344705");
assertThat(MDC.get("correlation_id")).isNull();
```

- [ ] **2 — Green logging/instrumentation.** Use the B1-resolved compatible JSON encoder, UTC timestamp, service/environment/version/event_name/level/safe message and diagnostic IDs. Remove existing contact-address logging and cloud Hibernate bind/SQL logging. Agent attributes use an allowlist; parameter/raw SQL/request headers and exception text cannot reintroduce secrets. Emit technical-failure counter after rollback, distinguish expected 4xx/409/422. Domain objects import no telemetry SDK. Java agent log forwarding off; Kubernetes forwarder is the only New Relic app-log sender.
- [ ] **3 — Green trace/probe paths.** Persist validated originating traceparent/correlation with outbox, extract it for publisher span and inject producer context into outgoing event; consumer extracts and clears context between reused invocations. API Gateway ID remains a separate correlation field. Configure these Spring health groups and I6 probes:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
      group:
        readiness:
          include: readinessState,db
        liveness:
          include: livenessState
```

Startup liveness every 5 seconds/24 failures, normal liveness every10/3 failures, readiness every10/3 failures. External public `/health` is gateway-mapped status only. SES/SQS/telemetry outages do not affect these health groups.
- [ ] **4 — Verify.** Run named tests plus security/regression suites. DB outage must make readiness DOWN while liveness stays UP. Validate JSON/privacy across app logs, gateway allowlist and agent payloads, including exceptions. Pin standard sampled tracing and initial app span reservoir 500/harvest; Lambda layer equivalent is validated for the selected version. Measure cold starts later; no assertion that all error traces are retained.
- [ ] **5 — Commit.** Stage named instrumentation/config/tests in APP/FUN; `git commit -m "feat: correlate private telemetry and health checks"`.

### R2: Export business snapshots and provision dashboards/alerts

**Files:** APP Create `src/main/java/com/oficina/adapter/out/observability/NewRelicSnapshotExporter.java`, `src/main/java/com/oficina/application/observability/SnapshotScheduler.java`, `src/test/java/com/oficina/adapter/out/observability/SnapshotExportTest.java`, `observability/dashboard-queries.json`; K8S Create `observability/newrelic-values.yaml`, `infra/monitoring/`, `infra/monitoring/tests/monitoring.tftest.hcl`; FUN Create `infra/modules/functions/alarms.tf` and alarm tests.

**Interfaces:** Scheduler consumes A7 RelatoriosPort/Clock, invokes `NewRelicSnapshotExporter.exportar(List<Map<String,Object>> eventos): void`. HTTP exporter accepts endpoint/account/ingest key through configuration and a testable HTTP client. Business events go through the Event API once; the Java agent handles separate APM signals. Every event has `eventType`, `environment`, `timestamp` set to capture epoch milliseconds and explicit period/status fields. Custom snapshot timestamps must not be replaced by network arrival time.

- [ ] **1 — Red exporter/query test.** Use a local fake HTTP receiver, fixed clock and fake reporting port. Verify 7 daily plus one rolling-window aggregate, current-status gauges and outbox/heartbeat events. No query connection remains open when export starts. A duplicated replica export does not change displayed counts/means; sample count zero and stale snapshots yield N/A/no data.

```java
Map<String,Object> event = exported.get(0);
assertThat(event.get("eventType")).isEqualTo("WorkshopReportSnapshot");
assertThat(event.get("timestamp")).isEqualTo(clock.instant().toEpochMilli());
assertThat(event).containsEntry("diagnosis_total_seconds", new BigDecimal("3600"))
    .containsEntry("diagnosis_samples", 2L);
```

- [ ] **2 — Green export.** Every60 seconds with jitter, query A7 projections under its 2-second statement timeout; release DB connection, then send a bounded batch with 2-second HTTP timeout and no unbounded retries. Fields use exact `diagnosis_total_seconds`/`diagnosis_samples`, `execution_total_seconds`/`execution_samples`, `finalization_total_seconds`/`finalization_samples`, `created_count`, eligible/excluded counts and period bounds. Current status events carry known/unknown age counts. Export zero totals/counts explicitly. Failures/drops increment safe telemetry diagnostics; never fail the business transaction.
- [ ] **3 — Green dashboards/agents.** New Relic Terraform builds the four approved dashboards and free simple-ping monitors every minute/environment. Dashboard queries choose the latest period snapshot in a 3-minute freshness window, for example:

```sql
FROM WorkshopReportSnapshot
SELECT latest(diagnosis_total_seconds) / latest(diagnosis_samples) / 60
WHERE environment = 'staging' AND window_kind = 'day'
FACET business_date SINCE 3 minutes ago
```

Use the corresponding rolling-window snapshot for seven-day means; no average of daily averages. Verify zero division renders N/A and capture-time ordering wins against delayed older exports. Choose environment through a finite dashboard filter, not a caller-built raw NRQL string.

Install a minimal pinned nri-bundle with low-data mode, explicit 60-second target, only approved infrastructure/KSM/log collectors; no Pixie/duplicate Prometheus/auto-attachment. One shared-cluster collector can use one of the two existing environment ingest-secret references for shared telemetry with explicit cluster/environment labels; workload credentials remain separate. Do not create a third AWS secret implicitly. Pin Java/slim Lambda layer and extension; extension alone forwards function logs, no forwarding Lambda. Resolve dependencies/keys outside Terraform values and keep provider user API key out of workload configuration.
- [ ] **4 — Green alerts/verify.** Reproduce every step 4C threshold: order technical failures, sustained uptime/error ratio, outbox BLOCKED/age, capacity/pending pods, integration failures, missing heartbeat/drop/usage. Native FUN alarms cover source age>300 seconds for2 one-minute periods, DLQ depth>0 and Lambda throttles; metric dimensions identify the exact environment resources. SNS topics/verified operator subscription handle native alarms; New Relic sends its own operator alerts. Missing AWS data is displayed as unknown, not proof of health. Run exporter tests, Terraform tests/fmt/validate and chart resource accounting; actual alert delivery/cold-start/volume checks remain R4.
- [ ] **5 — Commit.** Stage export/query/chart/monitoring/alarms/tests per owner; `git commit -m "feat: expose business and infrastructure observability"`.

### R3: Deliver architectural documents and runbooks with each feature

**Files:** APP Create `docs/phase-3/README.md`, `docs/phase-3/architecture/components.md`, `authentication-sequence.md`, `order-opening-sequence.md`, `data-model.md`; `docs/rfcs/001-aws-profile.md`, `002-postgresql-model.md`, `003-cpf-authentication.md`, `004-notifications.md`, `005-observability.md`; `docs/adrs/001-modular-monolith.md`, `002-environment-scaling.md`, `003-token-trust.md`, `004-outbox-delivery.md`, `005-canonical-reporting.md`; update existing DDD/Postman docs after explicit file audit. All four repositories Modify README.md and create their `docs/architecture.md`. APP Create `scripts/check-doc-links.py` and contract snapshots under `docs/phase-3/api/`.

**Interfaces:** Documentation index links immutable API/schema/event revisions and each owning repository's architecture. Link checker returns nonzero for unresolved local links or required missing files; it does not call a missing deployment an active one.

- [ ] **1 — Inventory required documents.** Turn step 5's documentation matrix into exact file checks. Use Python stdlib link extraction for local markdown files; external URLs receive an authenticated/manual availability check during R5, not a false pass from HTTP status on a login page. Do not invent a unit test for prose.
- [ ] **2 — Write architecture evidence.** Components include the two APIs, auth/customer/staff flow, EKS/RDS, state stores/queues and monitoring. Sequence diagrams show OTP consumption/failure and staff order transaction→outbox→asynchronous notification. ER describes actor FKs/checks, optimistic/identity versions, canonical history, outbox and indexes. Preserve four documented DDD contexts. Each RFC states alternatives/outcome; each ADR records decision/consequences and links implementation.
- [ ] **3 — Finish READMEs/runbooks.** Include actual technologies, prerequisites, exact local/test/deploy commands, CI triggers, architecture, API links and environment/window status. Infra repos link the application API and explain no local API/Dockerfile where inapplicable. Runbooks cover bootstrap, first-writer interruption, compatible rollback, key rotation, diagnosed failures, inspected DLQ/outbox recovery, evidence export and cleanup. No high-availability/exactly-once claim beyond evidence.
- [ ] **4 — Verify.** `python scripts/check-doc-links.py docs README.md`; manually render Mermaid and compare labels/relationships with applied schemas/manifests. Export OpenAPI/Postman without credentials. Required generated outputs become committed artifacts or durable release attachments with verified links.
- [ ] **5 — Commit.** Stage exact docs/checker/API exports; `git commit -m "docs: explain phase 3 architecture and operations"`.

### R4: Rehearse AWS deployment, failure/recovery and credit limits

**Files:** APP Create `scripts/rehearsal/check-readiness.ps1`, `smoke.ps1`, `verify-evidence.ps1`, `recover-outbox.ps1`, `replay-notification.ps1`; `docs/phase-3/evidence/manifest.json`, `docs/phase-3/evidence/cloud-window.md`, `docs/runbooks/cleanup.md`; tests `tests/rehearsal-contract.ps1`. K8S/FUN/DB contribute their real outputs/plan/run evidence.

**Interfaces:** `check-readiness.ps1 -InputFile` fails if FREE/credit/quota/identity/window/plan/capacity evidence is missing or invalid. `smoke.ps1 -Environment -ReleaseManifest` uses selected synthetic fixture identities and keeps temporary tokens in memory only. Evidence manifest contains requirement ID, environment, source/artifact/plan revision, timestamp, expected/observed result, PASS/FAIL/NOT_RUN and durable evidence links. Recovery commands accept inspected event IDs plus operator/reason and require an explicit action switch; default mode only previews.

- [ ] **1 — Dry-run the prerequisites.** Test closed window, root identity, insufficient remaining credit, wrong environment/digest, missing secret/view and failed migration scenarios. Scripts must refuse apply/release, not skip to a green result. Recheck actual service eligibility/versions/prices, remaining credits and capacity. Obtain confirmed repository owner/visibility and authorization for external repository/protection setup before publishing prepared repositories; do not guess destinations. The operator confirms SES addresses and New Relic/SNS access through their normal enrollment flows.
- [ ] **2 — Open the bounded window and deploy.** Record UTC start/end and the current US$35 estimate/all retained costs. Execute I7's reviewed creation order with dedicated identities and protected branch merges. Capture eight repository/environment deployment records, exact image/JAR/source digests and rollback compatibility. Every actual resource apply is associated with the inspected plan and current allowed window; no root or paid upgrade. If creation eligibility fails, record and resolve that failure before claiming AWS completion.
- [ ] **3 — Run the acceptance matrix.** Demonstrate OTP login/negative/replay cases and independently protected APIs; staff creates/transitions an order, customer reads/decides only their own; old email-token mutation is unavailable. Real PostgreSQL/DynamoDB/SQS cases verify one successful OTP consumption, optimistic rollback, FIFO predecessor rules, five-receive DLQ and stale replay suppression. Inject failure only into synthetic staging scenarios, recover and capture alert/log/trace evidence. Verify SES inbox receipt separately from SES_ACCEPTED. Test HPA with bounded in-cluster authenticated traffic, gateway separately, then verify scale-down/recovery.
- [ ] **4 — Export and measure.** Capture the 30/50/20-minute SQL/dashboard fixture, namespace denial, readiness-vs-liveness outage behavior, agent/JVM/collector memory, cold starts, connections, actual telemetry ingestion and AWS projected costs. Stop discretionary load at telemetry80%; preserve margin for delayed metering. For BLOCKED retry/skip, preview exact event/order/dependencies, execute only the reviewed action and append `outbox_recuperacoes`. DLQ replay retains original event ID and applies the approved stale policy; no automatic bulk redrive. Capture evidence before one-day logs expire.
- [ ] **5 — Record the result and cleanup proposal.** Run `./scripts/rehearsal/verify-evidence.ps1`; fail if a required outcome is NOT_RUN/FAIL or a link/digest is absent. Commit redacted evidence metadata as `test: record phase 3 cloud acceptance`. Prepare the exact resource/export/retention/cost cleanup list for destructive-action confirmation; do not destroy or schedule an automation just because the window has an end. After separately authorized cleanup, disable pings/mute operational alerts and mark endpoints offline truthfully.

### R5: Prepare the video and single submission PDF

**Files:** APP Create `docs/phase-3/submission/video-script.md`, `submission-manifest.json`, `scripts/submission/build_pdf.py`, `scripts/submission/check_submission.py`; output `artifacts/phase-3-submission.pdf` and the evidence/video source records. These scripts use a pinned local PDF library/runtime resolved at execution, not a paid cloud rendering service.

**Interfaces:** Submission manifest requires `repositories` (exactly four verified URLs), `videoUrl`, `videoDurationSeconds`, `documentationUrls`, `reviewerUsername="soat-architecture"`, per-repository verified access evidence and `releaseRevision`. Renderer emits one PDF with readable clickable links. A missing external publishing destination is a required input, not a fabricated URL.

- [ ] **1 — Prepare the 14-minute script.** Use step 5's five timed chapters: 00:00–01:30 architecture; 01:30–05:30 auth/orders; 05:30–08:30 CI/CD; 08:30–12:30 dashboards/traces/failure/recovery; 12:30–14:00 data/docs/results. Capture real long-running deployment records beforehand and disclose time compression; no claim a skipped step ran live.
- [ ] **2 — Record/review.** Demonstrate actual versions from R4 with synthetic data and no tokens/OTP/credentials/private identifiers visible. Check readable code/dashboard text and duration≤900 seconds. Obtain the explicit publishing instruction/destination before YouTube/Vimeo upload or reviewer invitations; keep prepared files ready for that final external action.
- [ ] **3 — Verify access and inputs.** After authorized upload/invitations, verify the video is playable and the reviewer has access to every repository; pending invitations do not become confirmed access automatically. Implement the input checks before rendering:

```python
assert len(manifest["repositories"]) == 4
assert 0 < manifest["videoDurationSeconds"] <= 900
assert manifest["reviewerUsername"] == "soat-architecture"
assert all(r["reviewerAccessVerified"] for r in manifest["repositories"])
assert manifest["videoUrl"].startswith("https://")
assert manifest["documentationUrls"]
```

- [ ] **4 — Render/check PDF.** `python scripts/submission/build_pdf.py --manifest docs/phase-3/submission/submission-manifest.json --output artifacts/phase-3-submission.pdf`. Use ReportLab (or the configured bundled PDF runtime when executing) to draw title, release context and four repository/doc/video/access sections with clickable links and page breaks. Open/render every page and test all links from the exported PDF; `check_submission.py` checks manifest and output existence/required entries. Portal submission is a separate explicitly instructed external action with the finished PDF ready.
- [ ] **5 — Commit/deliver.** Commit scripts/redacted submission metadata and durable artifact references as `docs: prepare phase 3 submission package`. Final completion report distinguishes verified deployment/evidence, submitted artifacts and any remaining user-owned publication/access action. Never mark a missing requirement complete to close the plan.
