# Phase 3 Functions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver serverless CPF authentication, route authorization and durable status-email consumption.

**Architecture:** Plain Java use cases consume narrow lookup/state/signing/email ports. Lambda, JDBC, DynamoDB and SES are adapters; functions have no JPA entities or write access to customer/order/stock tables.

**Tech Stack:** Java 17, the B1-pinned AWS SDK v2/Lambda libraries, Jackson, JJWT, JUnit/Mockito/AssertJ, PostgreSQL test containers.

**Spec:** [Auth](../specs/2026-09-14-phase-3-design.md), [identity](../specs/2026-09-14-phase-3-data-design.md), [delivery](../specs/2026-09-14-phase-3-notifications-design.md); [plan index and wire fixtures](2026-09-15-phase-3-implementation.md).

## Global Constraints

CPF only for login; 6-digit code; 5-minute validity; 5 verification attempts; 60-second resend cooldown. Customer JWT RS256, 15 minutes, no refresh, `identity_version`; staff HS256 separately validated. SQS FIFO, batch 1, source retention 4 days, visibility 120 seconds, maxReceiveCount 5, DLQ retention 14 days; notification Lambda 1 GiB/20 seconds and maximum concurrency 2 per environment. Shared Lambda account quota 10, no reserved/provisioned concurrency. No AWS apply in F1–F5.

## File map and ports

All Java paths below are relative to FUN `src/main/java/com/oficina/functions/`, tests to `src/test/java/com/oficina/functions/`. Keep packages `auth`, `notification`, `adapter/aws`, `adapter/jdbc`, `handler` and `bootstrap`. Build one shaded JAR containing separate handler entry points; pin the same JAR digest for staging/production.

F1 owns these records/interfaces in `auth/` (each in its own file):

```java
public record ClienteSnapshot(UUID id, String cpf, boolean ativo,
    String email, long versaoIdentidade) {}
public interface ClienteLookup {
    Optional<ClienteSnapshot> porCpf(String cpf);
    Optional<ClienteSnapshot> porId(UUID id);
}
public record Desafio(UUID id, String cpfHash, UUID clienteId,
    long versaoIdentidade, byte[] salt, byte[] hash, Instant expiraEm) {}
public interface DesafioStore {
    boolean emitir(Desafio desafio, String origemHash, Instant agora);
    Optional<Desafio> verificarEConsumir(UUID id, String codigo, Instant agora);
    void invalidar(UUID id);
}
public interface EnviarCodigo { void enviar(String email, String codigo); }
public interface GeradorCodigo { String gerar(); }
public interface TokenSigner { String emitir(ClienteSnapshot cliente, Instant agora); }
public record EmissaoDesafio(UUID desafioId, int expiraEmSegundos) {}
public record TokenResposta(String accessToken, String tokenType, int expiresIn) {}
```

Records holding byte arrays copy them defensively. A missing/inactive customer's dummy challenge has no customer UUID and can never issue a token. Hashes of CPF/source identifiers are pseudonymous, not anonymous; keep them in the protected short-lived state store, never logs/metric labels. F1 also owns `AutenticacaoException(int status,String codigo)` for safe boundary errors and `OtpHasher.hash(String,byte[]): byte[]`/`verificar(String,byte[],byte[]): boolean`.

### F1: Implement authentication use cases with deterministic tests

**Files:** Create the records/ports above, `auth/Cpf.java`, `auth/CriarDesafio.java`, `auth/VerificarDesafio.java`, `auth/OtpHasher.java`, `auth/SecureCodigoGenerator.java`; Test `auth/CpfAuthenticationTest.java`, `auth/OtpHasherTest.java`, `support/InMemoryDesafioStore.java`.

**Interfaces:** `CriarDesafio.executar(String cpf,String origem): EmissaoDesafio`, constructor consumes lookup/store/email/code generator/hasher/Clock; `VerificarDesafio.executar(UUID id,String codigo): TokenResposta`, constructor consumes lookup/store/signer/Clock. InMemoryDesafioStore implements the same atomic contract with synchronized methods for unit tests, not as production persistence.

- [ ] **1 — Red.** Stub active customer/registered email, deterministic code `123456`, fixed clock and signer `signed-token`; run `CpfAuthenticationTest`.

```java
@Test void challengeIsConsumableOnlyOnce() {
    EmissaoDesafio issued = criar.executar("390.533.447-05", "fixture-source");
    assertThat(verificar.executar(issued.desafioId(), "123456").accessToken())
        .isEqualTo("signed-token");
    assertThatThrownBy(() -> verificar.executar(issued.desafioId(), "123456"))
        .isInstanceOf(AutenticacaoException.class);
}
```

- [ ] **2 — Green issue/verify.** Validate normalized CPF checksum/repeated-digit rejection. Generate cryptographic six digits; hash with per-challenge random salt using PBKDF2WithHmacSHA256 (120,000 iterations, 256-bit output), compare with `MessageDigest.isEqual`. Persist state before sending only to the lookup's registered address. Unknown/inactive customers follow the same acknowledgement shape/rate policy but send no email. On SES failure invalidate the issued challenge and return safe 503; no email/code in logs.
- [ ] **3 — Red/green matrix.** Expiry at exactly 300 seconds fails; fifth wrong attempt exhausts; resend invalidates the prior challenge; two successful verifications race with one winner. After consumption re-read customer by UUID and require active/current identity version before signing; failed signing/response requires a new challenge after cooldown. Invalid CPF →400; invalid/expired/consumed code →401; applicable shared rate limit →429; unavailable lookup/store/signing/email →503. No customer refresh token.
- [ ] **4 — Verify.** Run `CpfAuthenticationTest,OtpHasherTest` and `verify`; assert generic response contains only opaque challenge ID/300 seconds, never email/UUID/token. Known and unknown syntactically valid CPF attempts use the same per-CPF issuance policy so cooldown status does not become a simple existence oracle. Do not claim constant-time network/database behavior.
- [ ] **5 — Commit.** Stage auth/fixture tests; `git commit -m "feat: implement CPF challenge use cases"`.

### F2: Implement atomic DynamoDB state and restricted customer lookup

**Files:** Create `adapter/aws/DynamoDesafioStore.java`, `adapter/jdbc/JdbcClienteLookup.java`, `bootstrap/ConnectionProvider.java`; Test `adapter/aws/DynamoDesafioStoreTest.java`, `adapter/jdbc/JdbcClienteLookupTest.java`; Create `docs/challenge-state.md`.

**Interfaces:** Implements F1 ports. DynamoDB item kinds share one challenge table with string PK: `challenge#{uuid}`, `issue#{cpfHash}`, `source#{origemHash}#{5-minute-bucket}`. Challenge stores salt/hash/expiry/attempts/consumed flag and current issuance reference. TTL epoch seconds is cleanup only. `ConnectionProvider.connection(): Connection` supplies at most one reusable JDBC connection per Lambda execution environment, stale-connection recovery and TLS certificate validation.

- [ ] **1 — Red adapter requests.** Capture AWS SDK requests using Mockito; assert the consume transaction contains a conditional current-challenge check plus an attempts/expiry/not-consumed CAS, and no blind `PutItem` replacement. Run `DynamoDesafioStoreTest`.

```java
verify(dynamo).transactWriteItems(captor.capture());
assertThat(captor.getValue().transactItems()).hasSize(2);
assertThat(captor.getValue().transactItems().get(1).update().conditionExpression())
    .contains("attempts", "expiresAt", "consumed");
```

- [ ] **2 — Green issuance/consumption.** Issuance atomically writes challenge, updates the per-CPF current pointer only after its 60-second cooldown, and increments a source bucket capped at 10 issuance attempts per 5 minutes/environment. Apply the same bounded policy to unknown/inactive valid CPFs; clean up dummy challenges normally. Verification reads consistently, computes hash locally, then transactionally checks current pointer and updates attempts/consumed with the observed attempt count and unexpired condition. Wrong code consumes one attempt; correct code atomically consumes the challenge. Conditional contention retries at most 3 reads, then safe failure; no token from an uncommitted consume. DynamoDB TTL delay never extends validity.
- [ ] **3 — Red/green lookup.** In PostgreSQL create the APP-owned auth view and a SELECT-only test login. Query `SELECT id,cpf,ativo,email,versao_identidade FROM auth_cliente_snapshot WHERE cpf=?` (or `id=?`). Assert CPF lookup works, CNPJ is absent, injection-shaped input is a parameter, base-table SELECT/UPDATE is denied and contact changes are visible on the next lookup. Bound connection/query time, close invalid connections and never log JDBC credentials.
- [ ] **4 — Verify.** Run both named tests. Mark actual AWS conditional-transaction races as R4 cloud acceptance, because mocked request-shape tests alone do not establish DynamoDB semantics. Local in-memory atomic tests remain useful for use-case behavior.
- [ ] **5 — Commit.** Stage adapters/tests/state contract; `git commit -m "feat: persist atomic authentication challenges"`.

### F3: Issue customer tokens and authorize explicit routes

**Files:** Create `auth/RsaTokenSigner.java`, `auth/CustomerTokenVerifier.java`, `auth/StaffTokenVerifier.java`, `auth/RoutePolicy.java`, `auth/Authorizer.java`, `auth/VerifiedPrincipal.java`; Test `auth/TokenAndRoutePolicyTest.java`; consume B2 `token-claims.json`/`routes.json` and A3 fixtures semantically.

**Interfaces:** `VerifiedPrincipal(String principalType,String subject,Set<String> permissions)`; both verifier classes expose `verificar(String token): VerifiedPrincipal`. `Authorizer.autorizar(String bearer,String routeKey): boolean` uses RoutePolicy; absent/invalid credentials are distinguished by the handler from valid-but-denied callers. Signer implements F1 `TokenSigner`. Customer private key belongs only to verification/signing runtime; authorizer gets public keys and separate staff verification secret.

- [ ] **1 — Red trust/route test.** Generate RSA and HMAC fixture keys locally. Decode signer output with the public key and assert exact approved claims/expiry; then test route scope:

```java
assertThat(routePolicy.permite(new VerifiedPrincipal("customer", customerId.toString(),
    Set.of("orders:read:self")), "POST /api/ordens-servico")).isFalse();
assertThat(routePolicy.permite(new VerifiedPrincipal("customer", customerId.toString(),
    Set.of("orders:read:self")), "GET /api/ordens-servico/{numero}/acompanhamento")).isTrue();
```

- [ ] **2 — Green token contract.** Sign RS256 with explicit `kid`, UUID `sub`, fixed environment issuer/audience, customer/access purpose, positive `identity_version`, scopes, `iat` and `exp=iat+900`. Use only configured public keys; reject unknown kid, token-supplied URLs, cross-environment tokens, algorithm confusion and staff refresh purpose. No DB ownership lookup in the gateway authorizer; APP performs it independently.
- [ ] **3 — Green route policy.** Load the versioned explicit route matrix. Default deny unknown route keys and wrong actor types/scopes/roles. Staff token role claims are coarse gateway checks only; APP re-reads current staff status/roles. No general authenticated catch-all. Disable authorizer-result caching in I5 and test environment role/config separation.
- [ ] **4 — Verify.** Run `TokenAndRoutePolicyTest,Phase3ContractTest`, and a contract fixture signed in FUN validated in APP's A3 test (public fixture keys only). Assert tampering/purpose/algorithm cases fail on both sides. Document public-key overlap through maximum JWT lifetime plus configured clock skew before retiring an old key.
- [ ] **5 — Commit.** Stage signer/verifiers/policy/tests; `git commit -m "feat: sign customer tokens and enforce route policy"`.

### F4: Adapt HTTP API events, SES OTP and Lambda packaging

**Files:** Create `handler/CriarDesafioHandler.java`, `handler/VerificarDesafioHandler.java`, `handler/AuthorizerHandler.java`, `handler/HttpResponses.java`, `adapter/aws/SesEnviarCodigo.java`, `bootstrap/FunctionFactory.java`; Modify `pom.xml`; Test `handler/HttpHandlersTest.java`, `adapter/aws/SesEnviarCodigoTest.java`; Create `contracts/phase3-v1/http-events/` fixture JSON files.

**Interfaces:** Challenge/verification handlers implement `RequestHandler<APIGatewayV2HTTPEvent,APIGatewayV2HTTPResponse>`. AuthorizerHandler implements `RequestHandler<Map<String,Object>,Map<String,Object>>` so its response can be either the explicit unauthorized object or v2 simple authorization response. FunctionFactory wires ports once per cold execution environment; tests inject use cases without loading AWS clients/secrets.

- [ ] **1 — Red HTTP fixtures.** Use full v2 event fixtures with requestContext HTTP method/source IP/route key. Assert 202/300-second challenge response and successful token envelope; malformed JSON/missing required fields return safe 400, not stack traces.

```java
APIGatewayV2HTTPResponse response = criarHandler.handleRequest(request, context);
assertThat(response.getStatusCode()).isEqualTo(202);
JsonNode body = mapper.readTree(response.getBody());
assertThat(body.path("data").path("expiraEmSegundos").asInt()).isEqualTo(300);
assertThat(body.toString()).doesNotContain("123456", "example.invalid", "accessToken");
```

- [ ] **2 — Green adapters.** Source throttling uses `requestContext.http.sourceIp`, never a caller-chosen identity field. Restrict parsed body size, unknown security-sensitive fields and supported content type. Map F1 safe status/code exceptions through the common envelope, correlation header and `Retry-After` for 429. Authorizer missing/invalid token returns `Map.of("errorMessage", "Unauthorized")`; valid identity with denied route returns `Map.of("isAuthorized", false)`. I5 omits identity sources and keeps TTL0 to support this documented 401 path; unexpected authorizer infrastructure failures remain errors. SES destination comes only from ClienteLookup; use fixed sender/template, direct OTP delivery and no customer-address override. Respect sandbox limits; SDK retries are bounded and failure invalidates the challenge. [Authorizer responses](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html)
- [ ] **3 — Package.** Configure the shaded JAR with Lambda/Jackson/JJWT runtime dependencies and preserved service resources. Add no Boot/JPA runtime. Handlers are distinct deployment entry points sharing the exact tested package. Add JSON-output adapter hooks for R1 without logging incoming bodies/tokens. No Function URL.
- [ ] **4 — Verify.** Run both named tests and `verify`; inspect JAR entries with `jar tf target/oficina-functions.jar`. Expect the three authentication entry points now; F5 adds the fourth notification entry point before I5 deploys the package. Set the shaded artifact final name to `oficina-functions` in this task. R4 verifies actual managed gateway 401/403 behavior instead of assuming local response fixtures prove it.
- [ ] **5 — Commit.** Stage handler/bootstrap/SES/package/fixture files; `git commit -m "feat: expose serverless authentication handlers"`.

### F5: Consume notifications with leases, deduplication and stale suppression

**Files:** Create `notification/StatusOrdemServicoRegistrado.java` (B2 wire record), `notification/Destinatario.java`, `notification/DestinatarioLookup.java`, `notification/DeliveryLedger.java`, `notification/ClaimResult.java`, `notification/StatusEmailSender.java`, `notification/NotificarStatus.java`, `adapter/aws/DynamoDeliveryLedger.java`, `adapter/aws/SesStatusEmailSender.java`, `adapter/jdbc/JdbcDestinatarioLookup.java`, `handler/NotificacaoHandler.java`; Test `notification/NotificarStatusTest.java`, `adapter/aws/DynamoDeliveryLedgerTest.java`, `handler/NotificacaoHandlerTest.java`.

**Interfaces:** `Destinatario(UUID ordemId,long numero,UUID clienteId,boolean ativo,String email,long versaoIdentidade)`; `DestinatarioLookup.porOrdem(UUID): Optional<Destinatario>`; `StatusEmailSender.enviar(Destinatario,StatusOrdemServicoRegistrado): String` returns SES MessageId. `DeliveryLedger.claim(UUID eventId,UUID ordemId,UUID owner,Instant now): ClaimResult`, `complete(UUID eventId,UUID ordemId,long sequencia,UUID owner,String outcome,String messageId,Instant now): void`; `ClaimResult` enum ACQUIRED/BUSY/TERMINAL and `long completedSequence(UUID ordemId)`. `NotificarStatus.executar(evento,String messageGroupId): void` throws on retryable failures; normal return permits SQS ACK. Ledger never replaces a terminal event or decreases the order cursor.

- [ ] **1 — Red deduplication test.** Use an in-memory conditional ledger fake local to the test and mocked recipient/sender; run `NotificarStatusTest`.

```java
notificar.executar(evento, evento.ordemId().toString());
notificar.executar(evento, evento.ordemId().toString());
verify(sender, times(1)).enviar(destinatario, evento);
```

- [ ] **2 — Green outcome policy.** Validate schema 1, event type, ≤8 KiB, allowed statuses and matching SQS group. Conditional claim owner/90-second lease; BUSY fails for retry, TERMINAL ACKs. Sequence at/below completed cursor → SUPERSEDED; missing/inactive or mismatched customer/version → SUPPRESSED; age over 24 hours → EXPIRED; valid current recipient → SES send then complete SES_ACCEPTED. Keep the timestamped historical template and no unauthenticated token link. Unsupported schema/dependency failures retry to DLQ, not silent ACK.
- [ ] **3 — Green DynamoDB completion.** Consistent reads, conditional lease owner and unexpired claim. Transactionally finalize the event and advance the per-order cursor monotonically; retry contention without resending when SES result is still in memory. Event/cursor retention 30 days; TTL is not correctness. Inject a crash after SES acceptance and before completion and assert retry may send again: record this residual duplicate window, not an exactly-once guarantee. Old DLQ replay is superseded when a later sequence completed.
- [ ] **4 — Verify.** Run all three named tests and `verify`. Test contact-change/deactivation, expired events, failed lookup/ledger/SES, malformed event, active lease and stale replay. SQS handler accepts one record and fails the invocation on retryable error; no Lambda async DLQ configuration. Actual visibility/receive-count/redrive behavior is verified in R4. Inspect the final shaded JAR's four handlers and contract hashes.
- [ ] **5 — Commit.** Stage notification/adapters/handler/tests; `git commit -m "feat: consume durable status notifications"`.
