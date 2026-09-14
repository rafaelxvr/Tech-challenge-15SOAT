# Phase 3 design specification — approval draft

Status: steps 1–4 are approved by the user. Step 5 and the complete written specification are ready for final review. This is a design specification, not an executable implementation plan or a claim that Phase 3 is complete.

AWS login was confirmed with a read-only STS check on 2026-09-14: profile `study`, region `us-east-1`, root identity. A read-only Free Tier check confirmed an active FREE account plan, US$100 remaining credits and plan expiration on 2027-03-14. No account identifier or credentials are recorded here. AWS is the intended final deployment target; Kind remains available for local development and CI. The user requires US$0 out of pocket using the free account only. Resource sizing, creation eligibility and deployment quotas still require validation. Deployment will use dedicated roles rather than the root session.

## Review sequence

| Step | Design section | State |
|---|---|---|
| 1 | Scope, DDD boundaries and engineering standards | Approved |
| 2 | CPF authentication, customer/staff permissions and integration contracts | Approved: permissions, CPF + email code, token lifecycle and contracts |
| 3 | AWS infrastructure, four repositories and staging/production delivery | Approved: 3A and [3B sizing, costs and delivery](2026-09-14-phase-3-aws-design.md) |
| 4 | Data changes, notifications and observability | [4A data](2026-09-14-phase-3-data-design.md), [4B notifications](2026-09-14-phase-3-notifications-design.md) and [4C observability](2026-09-14-phase-3-observability-design.md) approved |
| 5 | Acceptance evidence, documentation and implementation readiness | [Final acceptance specification](2026-09-14-phase-3-acceptance-design.md) ready for review |

We record approvals explicitly. The final written-spec review includes step 5; after approval, use Superpowers writing-plans to produce executable TDD tasks. Earlier `PHASE_3_*PLAN.md` files provide historical research and candidate approaches; this specification and its approved companions are the implementation design authority.

## Source and current architecture

Assignment: the supplied `13SOAT - Fase 3 - Tech Challenge.pdf`, with the full text also supplied in this conversation. Current-project references:

| Evidence | What it establishes |
|---|---|
| `docs/DDD_DOCUMENTACAO.md` and `docs/DDD_DIAGRAMA_UNICO.md` | Ubiquitous language, four bounded contexts, aggregates, invariants and context relationships |
| `docs/architecture/c4-components-api.md` | Application responsibilities and the existing notification output port |
| `src/main/java/com/oficina/entity/OrdemServico.java` | Aggregate methods for totals, status transitions and history |
| `src/main/java/com/oficina/service/OrdemServicoService.java` | Use-case coordination, ownership-related checks, inventory updates and notification calls |
| `src/test/java/` and `pom.xml` | Existing automated tests and the 80% line-coverage gate for entity, validation and service packages |

Current implementation is a single Spring Boot application organized mainly by technical layer. Its documented contexts are not independently deployed services. Services depend directly on Spring Data repositories, and domain entities carry JPA annotations and broad Lombok setters. Notifications already have a port (`NotificacaoPort`). Therefore, fully isolated domain/application layers are an evolution target, not an existing property to assume.

## Step 1 — approved scope and boundaries

### Recommendation

Retain a single business application and evolve its modular boundaries around the documented DDD contexts. Separate the required function and infrastructure delivery responsibilities into four repositories. Introduce abstractions and refactor existing code only where a Phase 3 capability needs them; preserve unrelated code and behavior.

| Approach | Benefit | Cost / limitation | Recommendation |
|---|---|---|---|
| Incremental modular monolith | Retains working domain behavior while making ownership and dependencies explicit | Requires focused refactoring and dependency checks for affected modules | Preferred |
| Preserve current technical layers throughout | Smallest initial code movement | New authentication/integration work can deepen existing cross-layer coupling | Feasible fallback if deadline is restrictive |
| Independently deploy each business context | Separate release/scaling boundaries | Distributed transactions, contracts and operations add work not required by the brief | Do not adopt without a demonstrated need |

### Preserve the documented domain language

| Bounded context | Responsibilities retained | Phase 3 design boundary |
|---|---|---|
| Identidade & Acesso | Staff identity, roles, token validation | Add customer CPF authentication through a serverless entry point; define staff/customer identity explicitly |
| Cadastro de Clientes & Veículos | Active customer records, document validation and vehicle ownership | Remains authoritative for customer existence/status; expose a narrow lookup contract to authentication |
| Catálogo de Serviços e Peças | Service descriptions/pricing, parts and inventory rules | Retain stock invariants; test concurrency when changing order approval |
| Atendimento e Execução | `OrdemServico`, its items/history, estimate calculations and allowed transitions | Own business state changes; application layer coordinates authorization, persistence and integrations |

Notifications are initially a supporting integration capability. Observability and deployment are technical concerns. Neither becomes a new business bounded context just because it has its own runtime component.

The existing context map documents direct entity consumption between contexts. Replace that coupling selectively with application contracts where the new features require it. Do not claim the contexts are already isolated, and do not force remote calls between modules in the same application.

### Four delivery repositories

| Proposed repository | Owns |
|---|---|
| `oficina-functions` | Authentication function and any agreed notification function; function code, tests and deployment configuration |
| `oficina-k8s-infra` | Terraform for Kubernetes and agreed shared platform/network resources |
| `oficina-db-infra` | Terraform for managed PostgreSQL and its infrastructure configuration |
| `oficina-app` | Existing Java business application, schema migrations, image, deployment configuration and application tests |

These names are proposals. Four repositories do not correspond one-to-one with the four business contexts. Each deployed resource will have one owning repository/state; exact shared-network, gateway and schema-deployment contracts belong to step 3.

### Approved engineering rules

| Standard | Concrete rule | Verification when implementing |
|---|---|---|
| DDD + OOP | Keep domain terminology and aggregate behavior. Protect affected state transitions with intention-revealing methods. Introduce value objects where they enforce a real invariant; do not wrap every primitive. | Tests for transitions, ownership, active status, totals and stock; review aggregate mutation paths |
| SOLID | Give each use case a focused responsibility. Keep transport/cloud SDK concerns in adapters. Introduce narrow ports at new or changed external boundaries; preserve behavioral contracts. | Test use cases with port fakes; review dependencies and adapter substitutability |
| KISS + DRY | Reuse working logic; avoid generic frameworks, speculative microservices, and broad package rewrites. Do not share JPA entities across repositories merely to avoid repeated DTOs. | Explain each new abstraction and identify its actual consumer; use shared contracts/fixtures only when justified |
| TDD | For each new behavior or bug fix, add a meaningful failing test, implement the minimum, then refactor. Use characterization tests before changing untested existing behavior. | Observe the intended failing assertion, then passing tests; add integration/contract checks where unit tests cannot prove the behavior |
| Clean code + regression safety | Use clear names, explicit errors and focused methods; inject time/identity/external services where deterministic testing needs them. Improve only code relevant to the approved change. | Existing suite stays green; migrations preserve data; retain the current core coverage gate without claiming it proves correctness |

Pure domain/application code is the target for new isolated functionality. Removing every existing JPA annotation, setter or service dependency is not a Phase 3 prerequisite. Any broader change needs a concrete reason and its own agreed scope.

### Business behavior preserved unless explicitly changed in step 2

1. Order transitions and estimate totals remain governed by `OrdemServico`.
2. Vehicle/customer ownership and active-record checks remain enforced.
3. Approval preserves stock sufficiency and transactional consistency.
4. History retains the actor and timestamp of each state change, including the new customer actor model once designed.
5. Staff administration/mechanic privileges stay separate from customer privileges.

Public tracking and approval currently documented as unauthenticated will need explicit review against the CPF/JWT requirement. Preserving business outcomes does not mean preserving an authentication bypass.

## Step 2A — approved customer permissions

Approved decision: preserve staff responsibility for opening and executing service orders; let CPF-authenticated customers track and approve/reject only their own orders. Customer self-service opening is not required explicitly by the brief and changes the current workflow, so it is excluded from the initial scope.

### Alternatives

| Option | Behavior | Trade-off |
|---|---|---|
| Staff opens; customer tracks and decides estimate | Preserve existing actor responsibilities; add customer JWT and ownership checks | Recommended: focused security improvement with minimal business change |
| Customer also opens an order | Add a customer-specific creation use case bound to their identity/vehicles | More request validation, permissions and business rules to agree and test |
| Customer only tracks | Customer login cannot approve/reject estimates | Removes an existing customer capability; not recommended |

### Proposed permission matrix

| Action | CPF-authenticated customer | ADMIN / MECANICO |
|---|---|---|
| Open an order | Denied | Allowed, preserving current rules |
| List operational orders and view internal details | Denied | Allowed, preserving current rules |
| Track order / view customer-facing estimate | Own orders only | Retain operational visibility through staff endpoints |
| Approve or reject an estimate | Own orders only, subject to aggregate state/inventory rules | No new customer-decision override granted by this proposal |
| Diagnose, send estimate, finalize, deliver | Denied | Allowed, preserving current rules |

Administrative actions remain ADMIN-only. Customer authentication must not grant general access to customer registries, unrelated vehicles, catalogs or other routes merely because the request is authenticated. All existing routes must have an explicit role/ownership decision before the new customer token is enabled.

### Concrete changes to the existing flow

`GET /ordens-servico/{numero}/acompanhamento` currently allows anonymous access. Under this proposal it requires a customer token and returns only the authenticated customer's order view. The order number selects a resource; it never establishes identity.

`POST /ordens-servico/{numero}/aprovar` and the approval/rejection behavior behind `/orcamento/notificacao` must use the authenticated customer's identity for customer-initiated decisions. Keep one application use case for each business decision so a compatibility route cannot duplicate or bypass authorization. Route naming and machine-callback authentication are separate contracts to settle in step 2C.

If a legacy request still contains a document field during migration, that field must not establish the actor or override the token identity. Define request compatibility explicitly when approving the HTTP contract. Update OpenAPI, Postman and DDD descriptions of "Aprovação pelo Cliente" and "Acompanhamento Público" to match the new authenticated flow.

The existing email status-update endpoint uses a shared token and permits broad transitions. Its machine-identity contract must be reviewed separately; a customer token cannot authorize arbitrary operational transitions through this alternate entry point. This proposal does not approve retaining a public bypass or removing the integration without a migration design.

### Responsibility boundaries

| Boundary | Responsibility |
|---|---|
| Identidade & Acesso | Validate credentials/token and supply a typed customer or staff actor; detailed credential proof is the next decision |
| Cadastro | Supply the authoritative customer identifier/status and vehicle ownership |
| Application use case | Enforce the actor's permission and resource ownership before returning data or executing a command |
| `OrdemServico` / inventory domain | Enforce legal transitions, estimate invariants and stock rules; no HTTP/JWT parsing inside aggregates |
| Adapters and audit persistence | Translate transport/persistence concerns and retain correct customer/staff attribution without changing domain rules |

### TDD acceptance scenarios for this permission decision

1. Customer A can track their order; changing the order number to Customer B's order reveals no order data.
2. Customer A can decide their estimate when domain rules allow; Customer B's decision attempt changes neither history, status nor inventory.
3. A customer cannot create an order, read the staff operational list, or execute diagnosis/finalization/delivery through any route.
4. Missing/invalid credentials are rejected; supplying a matching CPF in the payload does not replace authentication.
5. Existing staff operations still work, and unauthorized/rejected operations leave persisted state unchanged. Gateway/API contract tests cover alternate entry points as well as the primary routes.

Step 2A approval accepts the actor/permission model only. Step 2B covers CPF proof, token lifecycle and staff coexistence; step 2C will cover API/function contracts, failure responses and integration authentication. No part of those decisions is silently approved here.

## Step 2B — approved authentication and token lifecycle

### Approved decision

Use CPF to identify the customer, then a single-use code sent to the customer's registered email to prove control of that contact before issuing a customer access token. Keep the existing employee email/password login as a separate identity flow. The user approved the recommended email-code option and the proposed 15-minute customer JWT on 2026-09-14. CPF-only and customer passwords below are evaluated alternatives, not active features.

The PDF explicitly requires CPF validation, customer existence/status lookup and JWT issuance. It does not explicitly require an email code. The code is a proposed security enhancement: CPF validation alone checks an identifier, so someone who knows another active customer's CPF could obtain that customer's token. Ownership checks would then treat that token as the victim's identity. This is a limitation of the specified CPF-only flow, not a defect that JWT signing fixes.

| Option | What proves the identity | Additional work | Recommendation |
|---|---|---|---|
| CPF + email code | Possession of the registered mailbox after CPF/customer checks | Challenge state, email delivery, attempts/expiry and contact enrollment controls | Preferred for the planned cloud deployment |
| CPF only | Nothing beyond knowledge of an active customer's identifier | Smallest assignment flow; document impersonation limitation | Available if we deliberately keep to the literal coursework baseline |
| CPF + password | Knowledge of a previously enrolled customer password | Customer credential enrollment, password recovery and lifecycle | Not preferred: adds a credential-management feature |

`Cliente.email` is currently required, but the model has no verified-email flag. Existence of an address must not be described as verified ownership. If the email-code option is approved, define controlled staff enrollment and address changes, validate mailbox possession, and bind each challenge to the customer and address version. A login request cannot supply a replacement delivery address.

### Proposed customer flow

1. Customer submits CPF. Normalize digits, validate check digits, and resolve the active customer through a narrow Cadastro lookup contract. CNPJ remains valid for customer registration but is outside this CPF login flow.
2. For the recommended option, create a short-lived challenge and send the code to the registered email. Use a generic acknowledgement for unknown/inactive customers rather than disclosing registration status; issue no usable challenge/token for them.
3. Customer submits the challenge identifier and code. Validate expiry and attempt limit, recheck customer status/address version, and atomically consume the challenge. Persist no plaintext codes; use a protected verifier and never log codes or tokens. Failed email delivery does not issue a token.
4. Issue the customer JWT only after successful verification. A retry, replay or simultaneous second verification cannot reuse the consumed challenge.
5. Gateway and application validate the access token; application use cases still check active customer status and resource ownership before reading or changing an order.

The CPF-only alternative was not selected. Customer tokens require successful code verification; there is no automatic fallback to CPF-only if email delivery fails.

### Proposed defaults and token contract

These are design defaults, not PDF requirements. They are configurable and testable rather than scattered constants.

| Concern | Proposed rule |
|---|---|
| Approved email challenge | Cryptographically generated 6-digit code, 5-minute validity, maximum 5 verification attempts, 60-second resend cooldown; new challenge invalidates the previous one. Bound issuance by customer and source to prevent request spam. Exact gateway quotas belong to the deployment contract. |
| Customer access token | 15-minute lifetime; no customer refresh-token feature initially. Repeat the selected login flow after expiry. |
| Identity and purpose | `sub` is customer UUID; explicit customer issuer, API audience, issued/expiry timestamps, `principal_type=customer`, and scopes for own-order read/estimate decision. No raw CPF/email in token claims. |
| Signature | Propose RS256 with a key identifier and separate signing keys per environment. Signing material remains with the issuer; validators receive public keys. Pin accepted algorithms and define public-key publication/rotation in step 2C. |
| Deactivation and session ending | Protected customer use cases recheck the authoritative active status; deactivation therefore blocks subsequent access without waiting for expiry. Client logout discards its token; immediate revocation of an individual stolen token is not introduced silently. |

AWS's native HTTP API JWT authorizer validates RSA signatures and issuer/audience/time claims. It is compatible with the proposed asymmetric direction only when the required issuer/JWKS setup is provided. The current staff signer uses HMAC and is not interchangeable with that native authorizer. Gateway authorizer choice and how staff/customer routes coexist remain explicit step 2C decisions. [AWS JWT authorizer documentation](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html)

### DDD and implementation boundaries

Identidade & Acesso owns authentication policy, login challenges and token issuance. Cadastro owns the customer record, active flag and registered contact. The auth use case consumes a limited customer snapshot; it does not import application JPA entities into the function repository.

For the email-code option, authentication requires a transactional challenge store and email sender. Define these as narrow output ports with local fakes/adapters for tests. Select storage and delivery resources in the AWS design step; do not create new distributed services solely to satisfy an interface diagram. Customer snapshot lookup, challenge consumption and token issuance need explicit failure behavior in the next contract section.

Existing ADMIN/MECANICO users continue to authenticate with their current email/password flow and keep their approved permissions. Use distinct typed principals and explicit token trust configuration so customer claims cannot be interpreted as staff credentials. Any changes to the existing staff refresh-token behavior require a specific contract decision and regression tests.

### TDD acceptance scenarios

1. Malformed CPF, unknown customer or inactive customer never produces an access token; supplied CNPJ does not enter the CPF login path.
2. With email code enabled, a valid unexpired code for the active customer issues a token; incorrect/expired codes, exhausted attempts, changed contact and consumed challenges do not.
3. Tests with an injected clock verify challenge/token expiry; concurrent verification consumes a challenge once, and resend rules invalidate the previous challenge.
4. Tokens with wrong signatures, issuer, audience, purpose or expiry are rejected; a legitimate customer token never acquires staff privileges. Active-status and ownership checks remain effective.
5. Customer lookup, challenge storage and mail failures issue no token and leak no secrets in logs; existing staff authentication and permitted operations keep passing regression tests.

The email-code option is approved, including challenge-specific acceptance tests. No authentication code or cloud resources have been created during this design step.

## Step 2C — approved API, function and gateway contracts

Approved by the user on 2026-09-14, including staff re-login after token hardening and retirement of shared-token email status updates. The alternatives below record the options considered; the Lambda request authorizer is the selected approach.

### Recommended validation approach

Use two environment-specific AWS HTTP APIs, each with its own Lambda request authorizer and explicitly separate customer and staff token validators, as amended by approved step 3B. Keep the original bearer token available to the API, which independently validates it and applies current actor/status/ownership checks. Authentication logic stays in focused classes; a single authorizer entry point per environment does not require one large conditional implementation.

| Option | Benefit | Trade-off |
|---|---|---|
| Lambda request authorizer for both identity types | Keeps customer RS256 and existing staff HMAC signing as distinct trust contracts without an extra identity platform | An additional function invocation and validation code; recommended initially |
| Native JWT authorizer for customers, separate staff authorizer | Managed customer signature validation | Two gateway authorization configurations and a compatible issuer/JWKS publication setup |
| Migrate both identity types to a new common issuer immediately | One eventual token format/issuer | Wider staff-authentication migration than the assignment needs |

Start with authorizer-result caching disabled. Each route has an explicit allowed principal type and required scope/role; there is no general authenticated catch-all granting customer access to staff routes. The authorizer performs token validation and coarse route authorization, not business ownership queries. AWS documents Lambda request authorizers and route-specific cache keys if caching is introduced later. [AWS HTTP API Lambda authorizers](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html)

### Public HTTP contract

Paths below include the existing `/api` prefix. Success responses for the new customer operations follow the existing `ApiResponse` envelope (`timestamp`, `success`, optional `message`, `data`). Retain the existing staff login response shape. Treat OpenAPI and shared example fixtures as the cross-repository contract; do not distribute application DTO/JPA classes to functions.

| Endpoint | Caller / request | Success contract |
|---|---|---|
| `POST /api/auth/cpf/desafios` | Anonymous; `cpf` only | `202`; generic acknowledgement and `data.desafioId`, `data.expiraEmSegundos=300`. No token, customer UUID or destination email is returned. |
| `POST /api/auth/cpf/verificar` | Anonymous; `desafioId` and `codigo` | `200`; `data.accessToken`, `data.tokenType=Bearer`, `data.expiresIn=900` after successful verification |
| `POST /api/auth/login` | Staff; existing `email` and `senha` | Existing response field names; access token explicitly typed as staff/access |
| `GET /api/ordens-servico/{numero}/acompanhamento` | Customer bearer token; owned order | `200`; existing customer-facing projection, reviewed to include the estimate needed for an informed decision and exclude unrelated/internal records |
| `POST /api/ordens-servico/{numero}/orcamento/decisao` | Customer bearer token; `decisao` (`APROVADO` or `RECUSADO`), optional `observacao` | `200`; customer-facing order/estimate result after the domain transition, without an identity field in the request |

Requesting another challenge uses the same challenge-start endpoint and its approved cooldown/invalidation rules. For syntactically valid unknown/inactive CPFs, return the same acknowledgement shape with an opaque unusable identifier; verification returns the same generic failure as an invalid challenge. Acknowledgement does not promise that an email arrived. Failed delivery is observable and leaves no usable code/token; a later retry remains subject to the cooldown. Known-email delivery errors must not become an account-existence signal in the response.

Customer registration is not part of login. Registered email is obtained from Cadastro, never from these requests. Login challenges bind the customer/contact version and cannot be transferred to another CPF/address.

### Function and application boundaries

| Unit | Contract / responsibility | Dependencies |
|---|---|---|
| Challenge-start handler | Validate input, run challenge-start use case, map generic acknowledgement/errors | Customer lookup, challenge store, code sender, protected random generator, clock |
| Challenge-verification handler | Validate and atomically consume a challenge, recheck active status/contact, issue customer JWT | Customer lookup, challenge store, token signer, clock |
| Customer lookup adapter | Parameterized query of a narrow read-only customer view in PostgreSQL; return ID/status/contact/version only | Least-privilege DB connection; schema contract owned by application migrations |
| Gateway authorizer | Validate fixed issuer/algorithm/purpose and route permission; allow/deny without loading orders | Configured public customer keys and staff verification secret; route policy |
| Main API use cases | Independently authenticate actor, check active status and ownership, execute aggregate behavior, record audit | Existing business repositories/ports and transaction management |

The customer lookup is a deliberate limited database integration, not a new customer-data owner. Its view/contract changes through backward-compatible app migrations with tests in the functions repo. This follows the current context map's direct integration style while avoiding importing JPA entities. The function has no write permission to customer records. Authentication challenge storage and its lifecycle are separate from the customer lookup credentials; the concrete AWS storage is settled in step 3.

If verification consumes the challenge but signing or response delivery fails, issue no replacement code/token implicitly; the customer starts a new challenge after cooldown. Use a transaction/compare-and-set contract for challenge attempts and consumption. Expiry must be checked by the application even if the storage also supports automatic expiry cleanup.

### Token trust and staff compatibility

Customer JWTs retain the approved RS256 contract and 15-minute lifetime, with `token_use=access`. The customer signer alone holds the private key. Deploy an explicit public-key set to the API and authorizer; unknown key IDs are rejected and token-provided key URLs are ignored. This selected authorizer does not require a public OIDC discovery endpoint. Rotation publishes the new public key before switching the signer, overlaps the previous key through the maximum token lifetime plus permitted clock skew, then removes it.

Staff keep `/auth/login`, email/password verification, current staff subjects and configured access lifetime. Add explicit staff issuer/audience, `principal_type=staff`, and `token_use=access`; sign newly issued staff tokens with an explicitly pinned HS256 policy and a separate suitably sized secret. The API resolves the active staff user and current roles rather than allowing claims to manufacture an administrator. Customer RSA public keys must never be accepted as staff HMAC secrets. Stage/prod have separate key material and trust configuration.

The existing `LoginResponse` includes `refreshToken`, but no refresh exchange endpoint was identified in the reviewed auth controller. Retain that field for compatibility while marking any generated token `token_use=refresh`; reject it on every protected resource. This design adds no refresh endpoint. A dedicated test proves refresh tokens cannot substitute for access tokens.

Migration impact: previously issued staff tokens lacking the new purpose/trust claims will be rejected; staff must log in again after this auth release. This compatibility change was approved in step 2C. The login method and field names remain stable; update Postman and release notes to make the re-login explicit. New customer tokens do not add staff permissions.

### Existing routes and inbound email migration

Keep `/ordens-servico/{numero}/aprovar` and `/ordens-servico/{numero}/orcamento/notificacao` as documented compatibility aliases for authenticated customer decisions, invoking the same authorized use cases. If their legacy payload contains `documentoCliente`, require it to agree with the authenticated customer's record, but never use it to select or grant the actor. Omit the field from the new decision contract. Third-party callers are not implicitly trusted as customers.

Propose retiring `/ordens-servico/email/atualizar-status` as a shared-token state-change mechanism. Remove its public gateway route and deny it at the API before any mutation. Email notifications point the user toward authenticated customer operations; staff use the existing diagnosis/finalization/delivery endpoints. Remove the shared token requirement from deployment configuration when the endpoint is retired and update collections/documentation.

This is an explicit compatibility change, not a claim that an existing external producer has been migrated. No external producer integration was established by this review. If one must be preserved, replace retirement with a separately designed machine-authenticated contract before implementation. Outbound status email remains in scope for step 4.

### Failure responses

Preserve existing application error conventions: `timestamp`, numeric `status`, stable `code`, safe `message`, `path`; validation responses may include `errors`. Functions produce the same shape where they own the response. Framework security handlers must map API-level auth failures consistently because controller advice alone does not cover every filter failure.

| Failure | HTTP behavior | State/data guarantee |
|---|---|---|
| Malformed CPF/payload | `400 VALIDATION_ERROR` | No code/token issued |
| Bad/expired/consumed code, missing/invalid access token | `401` with generic credentials/challenge failure where controlled by function/API | No token, order mutation or identity disclosure |
| Wrong role/scope; another customer's or absent order | `403` for route privilege; same `404` result for absent/non-owned order | No cross-customer data or unauthorized mutation |
| Invalid order transition or insufficient stock | Preserve existing `422` business-rule response | Transaction rolls back; audit/stock remain consistent |
| Throttle or unavailable required dependency | `429` with retry guidance; `503` for unavailable lookup/store/signing service | Fail closed; no token or success claim; emit correlated diagnostic events |

API Gateway-generated failures may use the managed gateway's native JSON body. Document and test those separately rather than promising the app envelope for responses the app never handles. A missing/invalid identity should yield `401`; an authenticated route denial yields `403`; authorizer/integration infrastructure failures can yield gateway `5xx`. Correlation uses gateway request identifiers plus application trace context, without logging Authorization headers, CPF, email codes or signing material.

### Contract acceptance tests

1. Start and complete the approved CPF/email challenge through the gateway; response schemas and 15-minute access expiry match OpenAPI, and no customer refresh token is issued.
2. Unknown/inactive accounts and wrong/expired/replayed challenges never yield a token; resend, attempt limits, email failure and concurrent consumption are covered with deterministic clocks/fakes and storage integration checks.
3. Customer/staff validators reject cross-purpose, cross-environment, algorithm-confused and tampered tokens; a staff refresh token cannot call any resource, and staff can re-login after the migration.
4. Canonical and compatibility customer decision routes enforce identical ownership/domain rules; removed email-token routes cannot mutate an order at either gateway or API.
5. Run independent app/function tests against shared contract fixtures, plus a disposable end-to-end gateway scenario. Cloud gateway behavior is verified in staging; local checks are not proof of AWS configuration.

Step 2C approval settles the base contracts and two compatibility changes: staff re-login after token hardening, and retirement of shared-token email status updates. Approved step 4A additionally requires current customer `identity_version` checks and HTTP `409 CONCURRENT_MODIFICATION` for the specified concurrency conflicts. Approved step 4C adds anonymous `GET /health` without the `/api` prefix, rewritten by the gateway to minimal readiness status; no wildcard public Actuator route is allowed. These later amendments are detailed in their companions.

## Step 3 — approved AWS infrastructure and CI/CD

The [AWS sizing and delivery design](2026-09-14-phase-3-aws-design.md) contains the selected topology, resource ownership, cost worksheet and deployment prerequisites. It replaces the earlier candidate tables. Steps 3A and 3B are approved, including two environment-specific HTTP APIs in place of the original single-instance assumption.

| Decision | Approved outcome |
|---|---|
| Kubernetes and environments | One temporary EKS cluster with two fixed workers; staging and production namespaces and separate runtime identities. HPA scales application pods inside measured node capacity. |
| Gateway, network and data | Two HTTP APIs with Lambda authorization; shared private ingress/network foundation; separate private Single-AZ RDS PostgreSQL databases. The companion records the single NAT and internal HTTP limitations. |
| Infrastructure and delivery | Four repositories, one owner per resource, separated Terraform states, limited output contracts, GitHub OIDC and private CodeBuild deployment execution. PR merges to develop/main trigger staging/production deployment during an active cloud window. |
| Credit limits | Verified FREE account and US$100 balance as of the recorded read-only check; no paid-plan upgrade. US$35 planning allowance per initial 48-hour window, US$80 cumulative project allowance and US$20 separate reserve. Recheck actual credits, prices and eligibility before provisioning. |
| Operational limits | Local/CI Kind for daily work; cloud deployment explicitly blocked outside a scheduled study window. Verify shared Lambda/SES quotas, pod/IP/connection capacity and telemetry overhead. Destructive cleanup needs a concrete resource/data review. |

Shared EKS was selected over separate clusters to limit fixed costs and over self-managed Kubernetes to reduce cluster-lifecycle work. The topology does not provide full production high availability. Artifact promotion uses the exact tested digest/package; schema and runtime initialization follow the companion's dependency order. Actual branch protections, cloud creation eligibility and measured capacity remain deployment checks, not assumptions that a design approval proves.

## Step 4 — approved data, notifications and observability

| Companion | Approved decisions |
|---|---|
| [4A data integrity](2026-09-14-phase-3-data-design.md) | Current customer identity version in JWT/API checks; optimistic versions and HTTP 409; actor references, canonical ordered history, transactional outbox and exact reporting formulas. Includes the initial old-writer cutover interruption. |
| [4B notifications](2026-09-14-phase-3-notifications-design.md) | Outbox → SQS FIFO → Lambda → SES, restricted recipient lookup, duplicate/stale-message handling, bounded retries/DLQ and inspected recovery. |
| [4C observability](2026-09-14-phase-3-observability-design.md) | New Relic Free, minimal public health route, independent probes, correlated JSON/sampled traces, SQL snapshots, dashboards and native AWS queue/throttle alerts within existing allowances. |

These approved companions amend their named contracts and define implementation acceptance. No cloud provisioning or application implementation has been performed by this design review.

## Step 5 — acceptance and implementation readiness

The [final acceptance specification](2026-09-14-phase-3-acceptance-design.md) maps the PDF requirements to pass conditions and evidence, defines the documentation/submission inventory and divides implementation into five work packages. Step 5 and the complete written specification are ready for final review; the next activity is Superpowers writing-plans after that approval.

## Phase 3 completion coverage

These are the assignment outcomes covered by the approved technical design and step 5's proposed acceptance matrix. Their implementation and evidence remain to be produced.

| Outcome group | Required coverage | Evidence to define |
|---|---|---|
| Identity and API access | Gateway; serverless CPF validation, active-customer lookup and JWT issuance; protection of sensitive routes | Positive and negative auth tests, cross-customer access rejection, demonstrated gateway flow |
| Infrastructure and delivery | Four repos with CI/CD; protected main/master and PR merges; automatic staging/production cloud deployment; Terraform, scalable Kubernetes, managed database | Successful environment pipelines, resource/rollout evidence and repository settings |
| Operations and data | API latency, Kubernetes resources, uptime/health, order failure alerts, correlated JSON logs, daily orders, duration per status, integration errors; DB model justification | Dashboards, traces/logs, controlled failure/recovery, ER diagram and tested migrations |
| Architecture documentation | Component diagram; auth and order-opening sequences; RFCs and ADRs; purpose/technology/run/deploy/diagram/API links in each README | Reviewed documents that match the actual implementation |
| Submission | Video up to 15 minutes; single PDF linking four repos, docs and video; confirmation of reviewer access | Working links and demonstrated flows; permissions/publishing handled when explicitly requested |

The introduction also asks for serverless notifications. Its detailed mandatory list focuses on authentication; approved step 4B covers notifications through the outbox, SQS FIFO, Lambda and SES. Multiple units motivate the brief but do not by themselves require a new branch-management module. No UI, payment integration, full microservice split, or extra business feature is included by default.

## Approval record

| Item | Status |
|---|---|
| AWS login | Read-only STS check passed on 2026-09-14; `study` profile, `us-east-1`, root identity; dedicated deployment roles are designed but not created |
| AWS final deployment and local Kind development | User direction carried forward |
| Step 1 recommendation and standards | Approved by user: "approved, proceed" |
| Technical design | Steps 2A–2C, 3A–3B and 4A–4C approved |
| Final design review | Step 5 and the complete written specification await approval; executable TDD implementation planning follows |
| Implementation/provisioning | Not started by this specification |

Next action (under 1 minute): approve [step 5's five opening decisions](2026-09-14-phase-3-acceptance-design.md) and the complete linked specification so the executable TDD plan can be written. Provisioning has not started.
