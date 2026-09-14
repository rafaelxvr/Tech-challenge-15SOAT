# Phase 3 — step 5 acceptance and implementation readiness

Status: final design section awaiting approval. Steps 1–4 are approved. Parent: [Phase 3 specification](2026-09-14-phase-3-design.md). This section defines how completion will be proved; its acceptance criteria have not yet been executed.

## Five decisions for final review

1. Mark an assignment requirement complete only when its implementation, automated checks and applicable AWS demonstration evidence agree.
2. Implement in five bounded work packages using the approved DDD boundaries and TDD, preserving the existing Java/PostgreSQL foundation and coverage gate.
3. Maintain four repositories with consistent READMEs, versioned API/event contracts, architecture diagrams, RFCs, ADRs and database-model justification.
4. Produce a demonstration targeting 14 minutes and one submission PDF linking the four repositories, video, documentation and confirmed `soat-architecture` access.
5. Complete this written-spec review, then use Superpowers writing-plans to create executable tasks. Cloud rehearsal remains subject to the approved free-account, capacity and 48-hour operating limits.

Approval of this section also serves as the final review of the linked specification set. It moves the work to implementation planning without another duplicate section-approval round. It does not turn unexecuted checks into passes, grant permission to change repository visibility, or authorize destructive cleanup.

## Sources and decision precedence

The assignment baseline is the supplied `13SOAT - Fase 3 - Tech Challenge.pdf` and its full text pasted by the user. The source document describes the deliverables; the user's instructions establish the implementation scope, AWS direction, engineering standards and US$0 out-of-pocket constraint. Earlier local-only plans are historical research, not substitutes for the required cloud deployment.

| Specification | Authoritative responsibility |
|---|---|
| [Parent design](2026-09-14-phase-3-design.md) | Scope, four DDD contexts, engineering rules, customer/staff permissions and base HTTP/token contracts |
| [AWS design](2026-09-14-phase-3-aws-design.md) | Selected topology, two environment HTTP APIs, repository ownership, private deployment path, sizing and cost envelope |
| [Data design](2026-09-14-phase-3-data-design.md) | Identity version, optimistic concurrency/409, actor/history model, reporting formulas and first-writer migration cutover |
| [Notification design](2026-09-14-phase-3-notifications-design.md) | Outbox/FIFO/Lambda/SES contract, retries, residual duplicates, suppression and recovery |
| [Observability design](2026-09-14-phase-3-observability-design.md) | New Relic Free, minimal public health route, probes, snapshot exports, telemetry limits and native AWS alerts |

Later explicitly approved amendments govern their named subject: step 3B replaces the original single HTTP API instance; step 4A adds `identity_version` and concurrency handling; step 4C adds public `GET /health`. They do not silently replace unrelated business rules. Any implementation finding that requires a different topology, auth policy or spending envelope must be documented as a design change before using it.

## Assignment completion matrix

Every group below starts **not verified**. A checked box in a README or successful local test is not AWS evidence.

| Requirement group | Pass condition | Required evidence |
|---|---|---|
| Authentication and API Gateway | Deployed gateway routes through the serverless CPF flow; validate CPF, query existence/active status and issue a valid customer JWT after the approved email challenge. Customer/staff permissions, current identity version and ownership are enforced independently by the API. | Gateway-based positive login and protected order flow; wrong/expired/replayed code, inactive customer, wrong owner, wrong environment/purpose/algorithm and retired email-token-route rejection; redacted API examples and test results |
| Four repositories and delivery | `oficina-functions`, `oficina-k8s-infra`, `oficina-db-infra` and `oficina-app` each contain functional CI/CD. Protected main/master requires PRs. During an active window, merges to `develop` and `main` automatically deploy the applicable staging/production resources. Terraform provisions the cloud gateway, serverless functions, scalable Kubernetes and managed PostgreSQL. | Eight deployment records: staging and production for each repository, with commit/run IDs and outputs; actual branch-protection settings; image/package digests, Terraform plan/apply results, RDS identity and HPA scale-out/recovery evidence |
| Observability and notifications | Show API latency, Kubernetes CPU/memory, health/uptime, order-processing failure alerts, correlated JSON logs and traces. Dashboards show daily orders, the agreed per-status durations and integration failures. Serverless notifications follow the approved durable delivery behavior. | Live dashboard analysis, a selected correlated trace/log chain, controlled failure/alert/recovery, SQL-to-dashboard report comparison, status email and retry/DLQ evidence; no claim that SES acceptance proves inbox delivery |
| Architecture and database | Explain cloud/application components, authentication and order-opening sequences, relevant proposals/tradeoffs and permanent decisions. Justify PostgreSQL and the relational adjustments with ER diagrams and relationships. | Versioned component and sequence diagrams, RFCs, ADRs, ER model/dictionary, migration and index rationale, measured query/concurrency results and honest availability limitations |
| Repository and submission package | Each README describes purpose, technologies, execution/deployment, repository-specific architecture and applicable API documentation. Include Dockerfiles where applicable, CI/CD, applicable deployed links, a video at most 15 minutes and one PDF with all required links plus reviewer-access confirmation. | README/link checklist across four repositories, playable video, readable single PDF, accessible documentation/repositories and verified `soat-architecture` access to every repository |

Infrastructure-only repositories do not invent an API or Dockerfile. Their README links the application's API documentation and identifies the absence of a repository-local API/runtime where appropriate. Deploy links identify environment, verification time and the scheduled cloud window. After approved teardown, label the endpoint offline and keep historical evidence; do not represent a retired environment as an active deployment. The assignment's acceptance of a later offline link cannot be guaranteed by this specification, so schedule the window against the actual submission/review needs.

Cloud evidence must demonstrate the deployed gateway and RDS, not just a Kind cluster with containerized PostgreSQL. HPA evidence proves pod scaling inside the approved fixed worker capacity. The shared EKS cluster, Single-AZ databases, one NAT gateway and brief first-writer cutover are documented limitations; the video must not claim full production high availability or zero downtime.

## Engineering acceptance and test strategy

The current repository contains 24 Java test classes and four Flyway migrations, V1–V4. Its workflow runs `mvn -B verify`, builds an image and exercises Kubernetes in disposable Kind. JaCoCo currently enforces 80% line coverage for the included entity, validation and service code. These are inspected baseline settings, not a claim that today's test run passed.

| Layer | Required checks |
|---|---|
| Domain and use cases | Red → green → refactor for changed behavior: aggregate transitions/totals, stock consistency, actor authorization and identity-version rules. Use meaningful examples and injected clocks; preserve the four documented bounded contexts. No cloud SDK, New Relic API or Lambda handler dependency in domain entities/value objects. |
| Persistence and migrations | Real PostgreSQL integration tests for concurrent versions/409, transactional stock/history/outbox rollback, per-order publisher ordering, read-only views/roles and report formulas. Test fresh schema and upgrade from V4; preserve applied migration checksums and unknown legacy timestamp provenance. |
| API and functions | Versioned positive/negative HTTP, JWT, customer-view and event fixtures shared as contracts, not JPA classes. Test OTP expiry/replay/concurrent consumption, staff refresh rejection, customer isolation, delivery deduplication/suppression and dependency failure. AWS-specific conditional/queue/gateway behavior needs rehearsal verification beyond fakes. |
| Infrastructure and delivery | Terraform formatting/validation and inspected plans, manifest rendering, disposable Kind smoke checks, environment isolation, immutable artifact promotion, migration-before-rollout and failure propagation. No secrets or root sessions in CI; required jobs cannot pass by silently skipping their essential checks. |
| Operations and regression | Preserve existing business behavior except approved compatibility changes. Verify JSON privacy, probe behavior, report accuracy across replicas/time zones, retry/alert/recovery, measured resource/connection limits and selected end-to-end traces. |

Keep the existing `mvn -B verify` gate and at least its current coverage protection. When classes move to DDD-aligned packages, update the coverage selection so the moved business code does not disappear from the gate. Apply a meaningful equivalent gate to new function business/use-case code; do not meet it by excluding changed logic or testing only getters. A green coverage percentage does not replace the concurrency, security and failure scenarios above.

Keep JPA/Spring coupling changes incremental as approved. Place invariants inside existing aggregates/value objects, orchestration in focused application services and provider integration behind narrow ports/adapters. Avoid a generic framework, service-per-class split or a domain rewrite solely to satisfy a folder convention. SQL reporting projections serve read requirements without moving business transitions into query code.

Local/CI tests use deterministic fakes, fixture keys, PostgreSQL containers and Kind as needed. Ordinary PR verification must not require AWS/New Relic account credentials or a running cloud environment. Cloud-dependent jobs report an explicit blocked/not-run status outside the authorized window; they do not claim a successful deployment. Run the appropriate checks for each change; broaden the suite when integration risk or failures justify it.

## Five implementation work packages

The next Superpowers implementation plan will split these packages into exact files, failing tests, commands and expected results. These are dependency boundaries, not an estimate that all work takes five short sessions.

| Order | Deliverable | Exit condition |
|---|---|---|
| 1 — Baseline and contracts | Record current test/Kind results, preserve the DDD documentation, define the four repository contents and shared contract fixtures, establish CI/protection configuration as code or documented settings | Reproducible local baseline, tracked migration inventory, agreed package boundaries and contract examples; no accidental publication of existing untracked files |
| 2 — Application and data | Identity version, aggregate versions, actor/history adjustments, reporting queries, transactional outbox, protected customer decisions, staff-token hardening and retirement of the email-token mutation route | App regression/security/PostgreSQL tests pass, migrations work from V4, first-writer cutover/recovery is documented, and function lookup views/contracts are available |
| 3 — Serverless behavior | CPF challenge/verification and authorizer, FIFO publisher/notification handler, DynamoDB state adapters, SES adapters and bounded retry/recovery | Contract and failure tests pass locally; cloud-specific acceptance cases are identified with executable fixtures; functions cannot mutate customer/order/stock data |
| 4 — Deployment and observability | Terraform roots/outputs, OIDC/CodeBuild workflow, environment manifests, probes, agents, JSON logs, snapshots, dashboards and alerts | Local/render/plan checks pass; one-owner resource inventory and creation order are explicit; measured or clearly pending capacity/cost checks are listed before cloud apply |
| 5 — AWS rehearsal and submission | Create the bounded cloud environment through the reviewed workflow, demonstrate staging/production, verify acceptance, record the video and assemble the PDF/docs | Required evidence is complete and linked to exact revisions; remaining limits/issues are disclosed; evidence export and separately approved cleanup are ready |

Infrastructure source can be developed before package 3 finishes; deployment must respect the actual dependency graph. Bootstrap state/OIDC and network/deployment execution first, then database/cluster foundation, then application schema and lookup-role initialization, then function/queue bindings and app publisher rollout, then monitoring bindings. Use the detailed ownership and bootstrap contracts in steps 3B/4B/4C rather than making a repository depend on an output it must itself create.

## Documentation inventory

Use `oficina-app` as the documentation index. Keep infrastructure/function-specific details in their owning repository and link to them; do not maintain four diverging copies of the same specification. Preserve and evolve the existing DDD documentation rather than replacing it with generic cloud diagrams.

| Document group | Required content |
|---|---|
| Four READMEs and API contracts | Purpose, stack, prerequisites/version checks, exact local/test/deploy commands, environment variables/secret references, CI/CD triggers, architecture for that repository, applicable Swagger/OpenAPI/Postman link, deployment status/window and links to related repositories. Export OpenAPI/Postman without credentials; live Swagger follows the approved access policy. |
| Component and sequence diagrams | Global component diagram with AWS, the two environments, gateway/functions, EKS/app, RDS, challenge/delivery state, queues and monitoring; repository-specific views. Authentication sequence includes OTP, lookup, token and authorization failures. Order-opening sequence includes staff identity, aggregate transaction, history/outbox commit and asynchronous notification. |
| RFCs | Proposed documents covering AWS/free-credit topology, PostgreSQL/model evolution, CPF-plus-email/JWT authentication, asynchronous delivery, and observability/reporting. Each states alternatives, tradeoffs, decision outcome and affected contracts; link the approved design. |
| ADRs | Permanent decisions for the modular monolith/DDD boundaries, environment and scaling topology, customer/staff trust separation, transactional outbox/idempotent processing, and canonical timestamps/reporting/observability boundaries. Record consequences, including known limitations; use separate focused records where a topic contains independent decisions. |
| Data model and runbooks | Updated ER diagram and relationship/cardinality explanations, aggregate ownership, keys/FKs/checks/indexes and PostgreSQL justification. Include rollout/cutover, credential rotation, incident diagnosis, DLQ inspection/replay, evidence export and cleanup instructions with expected outcomes. |

Database justification must connect the choice to relational integrity, transaction boundaries across order/stock/history/outbox, concurrent updates and existing project compatibility. Include representative query plans and the report definitions. Do not claim an index improves performance without evidence, or rewrite unknown historical timestamps to make a diagram cleaner.

Required architecture documents are delivery artifacts to be created and aligned during implementation. The existence of this design checklist alone does not fulfill those document requirements.

## Evidence records and demonstration

Maintain a versioned, redacted evidence index in `oficina-app/docs/phase-3/evidence/`. Each acceptance record contains requirement/scenario, environment, UTC capture time, repository commit and artifact digest or Terraform revision, execution/run link, expected/observed result and evidence file/link. Use `NOT_RUN`, `PASS` or `FAIL`; explain any blocked check and do not relabel it PASS. Capture both failure and recovery where required.

Evidence files contain synthetic business data only. Redact tokens, OTPs, passwords, keys, email/contact data and account identifiers from screenshots and command output. Keep actual authentication secrets out of fixtures, git, pipeline logs and the video. Document corrections honestly; a simulated/local result must be labeled as such. Preserve exported results before one-day CloudWatch retention or default New Relic retention expires.

Target a **14-minute video**, leaving one minute below the PDF's maximum:

| Time | Demonstration |
|---|---|
| 00:00–01:30 | Problem, DDD/component view, four repositories and the actual study topology/limits |
| 01:30–05:30 | CPF/email challenge, valid customer token, staff order opening, protected customer order access/decision and one ownership/token rejection |
| 05:30–08:30 | PR/checks and automatic branch-driven staging/production deployment; show all four repositories' real execution records and selected immutable artifacts |
| 08:30–12:30 | Live API/Kubernetes/business dashboards, selected logs/trace, bounded HPA evidence, status notification and controlled failure/alert/recovery |
| 12:30–14:00 | ER/model changes, representative RFC/ADR, test results, evidence/documentation links and cloud-window/cleanup status |

Terraform/RDS creation does not need to finish in the recording's three-minute pipeline chapter. Record real executions beforehand and show their run IDs/results plus a concise live or recorded pipeline segment; disclose edits/time compression. Prepare failure fixtures and verify traces before recording so the demonstration remains within 15 minutes without pretending skipped deployment work ran.

The single submission PDF must contain the four repository links, one playable YouTube/Vimeo link, documentation links and confirmation that `soat-architecture` has been added to every repository. Verify access, not just that an invitation command succeeded. For each repository, record the actual access/invitation state and resolve any pending access before claiming confirmation. Publishing the video, inviting the reviewer and submitting to the portal require the user's instruction for those external actions; prepare all files and exact destinations first.

## Readiness checks before the AWS window

Missing account-specific values are recorded as deployment prerequisites, not hidden implementation decisions. They do not prevent local TDD and infrastructure authoring.

| Check | Required before deployment |
|---|---|
| Workstation and CI dependencies | Verify Java 17, Maven, Git, a working Docker engine, Kind, kubectl, Helm, Terraform compatible with the selected providers/native S3 locking, and AWS CLI v2. Pin compatible versions in the implementation plan/CI. PostgreSQL runs in containers locally; no global DB server or global New Relic agent is required. Record installed/missing tools; do not assume the earlier AWS CLI installation proves the rest are ready. |
| Identities and repository settings | Replace root deployment usage with dedicated human MFA and CI OIDC roles; select the actual owner/repository names and verify protection features for that visibility/account plan. Do not change visibility or buy a GitHub plan silently. Verify state access, narrow output contracts, deployment-role isolation and the actual settings in all repositories. |
| Free plans, credits and time | Recheck the current AWS FREE plan, remaining balance/expiry, chosen service eligibility and quotas; verify New Relic Free without paid features. Record exact opening/closing times against the submission/review schedule. Keep the approved US$35 window estimate, US$80 project allowance and US$20 separate reserve; reprice if reality differs. |
| Runtime capacity and external prerequisites | Verify allocatable node/pod/IP capacity, telemetry/JVM memory, HPA/rollout fit, RDS connection budgets, Lambda cold starts/concurrency, report-query/export behavior and actual ingestion. Inventory all secrets against the 16-secret assumption; confirm SES sandbox sender/recipients and New Relic/SNS operator-alert access. |
| Rehearsal, recovery and evidence | Review exact Terraform plans and releases; confirm migrations/first-writer cutover, rollback compatibility, synthetic data and executable smoke/failure scenarios. Prepare export paths and itemized cleanup/retained-resource costs before opening the window. |

Creating a plan is not proof that apply is eligible under the Free plan. If a selected service/configuration demands a paid upgrade or exceeds the measured envelope, stop that deployment and revise the concrete design; do not substitute local evidence or silently spend beyond the user's constraint.

Cleanup must name the actual resources, data exports/backups, retention choices and remaining costs for the user's destructive-action confirmation. The free-only constraint does not authorize deleting business data or removing resources without that review. Do not create scheduled cleanup/monitoring automations merely because this document describes a temporary window.

## Current status and next handoff

The design covers the supplied mandatory requirements and the approved serverless-notification objective. Implementation, cloud resource creation, functional verification, finished architecture deliverables and submission evidence remain future work. This turn reviews documentation; it does not claim new runtime tests passed.

After final approval, Superpowers writing-plans will turn the five work packages into executable tasks with file ownership, dependencies, exact verification commands, expected failures/results and evidence outputs. Local preparation can proceed while account-specific deployment prerequisites remain pending; no additional architecture round is needed for routine choices already covered here.

Next action (under 1 minute): approve the five opening decisions and the complete linked specification so the executable TDD implementation plan can be written.
