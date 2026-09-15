# Phase 3 Application and Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce customer identity/ownership, atomic order changes, durable notifications and correct business reporting.

**Architecture:** Keep the existing entities/services and improve the changed seams. New identity, notification and reporting types have narrow responsibilities; application services coordinate transactions and adapters implement persistence/provider concerns.

**Tech Stack:** Existing Java 17/Spring Boot/JPA/Flyway stack, PostgreSQL 16, JUnit/Mockito/AssertJ/Testcontainers; AWS SDK v2 only for the outbound queue adapter.

**Spec:** [Parent](../specs/2026-09-14-phase-3-design.md), [data](../specs/2026-09-14-phase-3-data-design.md), [notifications](../specs/2026-09-14-phase-3-notifications-design.md). Read the [execution index](2026-09-15-phase-3-implementation.md) first.

## Global Constraints

Java 17; Spring Boot 3.2.5; PostgreSQL 16; `mvn -B verify`; 80% line coverage protection. Do not edit applied V1–V4. Customer JWT lifetime is 15 minutes and carries `identity_version`. Concurrency conflicts return HTTP 409. Domain history uses one injected clock instant, not provider/security calls. No application implementation task provisions AWS.

## File map and shared types

All paths in this document belong to APP. Keep existing `entity`, `service`, `controller`, `repository` paths. Add `domain/identidade` for actor value objects, `security` for focused validators/context, `application/notificacao` and `adapter/out/outbox` for delivery, and `application/relatorio`/`adapter/out/relatorio` for reports. Every abbreviated Java path below is beneath `src/main/java/com/oficina/`; Test paths are beneath `src/test/java/com/oficina/`.

Create `support/PostgresIntegrationSupport.java` in A1: an abstract `@SpringBootTest` test base with a shared PostgreSQLContainer, `@DynamicPropertySource` JDBC settings, injected `JdbcTemplate`/`TransactionTemplate`, and Docker required. Each integration class seeds its own UUID fixtures inside PostgreSQL; no H2 substitution. Create `support/Fixtures.java` with `Cliente cliente(UUID id)`, `Veiculo veiculo(Cliente c)`, `OrdemServico ordem(Cliente c, StatusOrdemServico status)` using existing builders, plus `UUID` constants for two different synthetic customers and one staff member. Seed persisted fixtures through repositories so FKs and generated numbers are real.

### Task 1 (A1): Version customer identity and expose the narrow auth view

**Files:** Modify `entity/Cliente.java`, `service/ClienteService.java`, `repository/ClienteRepository.java`; Create `src/main/resources/db/migration/V5__versionar_identidade_cliente.sql`, `domain/identidade/DadosIdentidadeCliente.java`, `repository/ClienteIdentityAuditRepository.java`, `config/ClockConfiguration.java`, the support files above; Test `entity/ClienteIdentityTest.java`, `repository/ClienteIdentityPersistenceTest.java`.

**Interfaces:** `DadosIdentidadeCliente(TipoDocumento tipoDocumento, String documento, String email, boolean ativo)`; `Cliente.atualizarIdentidade(DadosIdentidadeCliente novos): void`, `Cliente.getVersaoIdentidade(): long`, `Cliente.getVersao(): long`. `ClienteIdentityAuditRepository.registrar(UUID clienteId,UUID staffId,Set<String> campos,long versaoAnterior,long versaoNova,Instant agora): void` uses JdbcTemplate in the service transaction. ClockConfiguration supplies injectable Clock. Normalize CPF/CNPJ with existing validation before comparison. View `auth_cliente_snapshot(id, cpf, ativo, email, versao_identidade)` includes CPF customers only.

- [ ] **1 — Red.** Create this unit test, then run `./mvnw.cmd -B '-Dtest=ClienteIdentityTest' test`.

```java
@Test void emailChangeInvalidatesOldIdentity() {
    Cliente c = Fixtures.cliente(Fixtures.CLIENTE_A);
    long before = c.getVersaoIdentidade();
    c.atualizarIdentidade(new DadosIdentidadeCliente(
        c.getTipoDocumento(), c.getDocumento(), "changed@example.invalid", true));
    assertThat(c.getVersaoIdentidade()).isEqualTo(before + 1);
}
```

- [ ] **2 — Green domain behavior.** Start identity version at 1 and row version at 0. Increment identity only when normalized document/type, email or active status changes. Route `ClienteService.atualizar` and `desativar` through that method, including future reactivation; ordinary address/name changes leave identity unchanged. Record changed field names, actor/time and old/new versions without logging raw contact values. Add tests for normalization-equivalent data, deactivation and non-identity edits.
- [ ] **3 — Red/green PostgreSQL.** Test persistent version increment, two stale customer updates and CPF/CNPJ view membership. Add:

```sql
ALTER TABLE clientes ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;
ALTER TABLE clientes ADD COLUMN versao_identidade BIGINT NOT NULL DEFAULT 1
  CHECK (versao_identidade > 0);
CREATE VIEW auth_cliente_snapshot AS
SELECT id, documento AS cpf, ativo, email, versao_identidade
FROM clientes WHERE tipo_documento = 'CPF';
```

Map `versao` with `@Version`. V5 also creates `cliente_identidade_auditoria(id UUID PK,cliente_id UUID FK,staff_id UUID FK,campos TEXT[],versao_anterior BIGINT,versao_nova BIGINT,ocorrido_em TIMESTAMPTZ)`. Service records changed field names/current staff ID and injected time in the same transaction; an audit insert failure rolls back the identity change. No raw old/new contact values. Restrict setter access for changed identity/version fields and adapt builders/tests; no native update may bypass the identity rule. Bootstrap GRANTs are handled in I3/I6, tested with real restricted roles there.
- [ ] **4 — Verify.** `./mvnw.cmd -B '-Dtest=ClienteIdentityTest,ClienteIdentityPersistenceTest,ClienteServiceTest' test`, then `verify`. A concurrent stale save must fail; the surviving identity version matches the committed record.
- [ ] **5 — Commit.** Stage the named entity/service/view/test files; `git commit -m "feat: version customer identity changes"`.

### Task 2 (A2): Add actor references, canonical history and optimistic aggregate versions

**Files:** Modify `entity/OrdemServico.java`, `entity/OsHistorico.java`, `entity/Peca.java`, `service/OrdemServicoService.java`, `exception/GlobalExceptionHandler.java`; Create `domain/identidade/Ator.java`, `domain/identidade/TipoAtor.java`, `src/main/resources/db/migration/V6__versionar_agregados_e_historico.sql`; consume A1 ClockConfiguration; Test `entity/HistoriaCanonicaTest.java`, `repository/ConcorrenciaAgregadosTest.java` and existing entity/service tests.

**Interfaces:** `enum TipoAtor { STAFF, CUSTOMER, SYSTEM, LEGACY_UNKNOWN }`; `record Ator(TipoAtor tipo, UUID id)` with factories `staff(UUID)`, `cliente(UUID)`, `sistema()`; live factory/constructor validation rejects LEGACY_UNKNOWN. Every existing transition method changes its parameters from `(UUID usuarioId, String observacao)` to `(Ator ator, Instant ocorridoEm, String observacao)`. `registrarHistoricoInicial` uses that same signature. `SecurityUtils.usuarioAutenticadoId()` remains staff-only until A3 supplies the general context.

- [ ] **1 — Red.** Run `HistoriaCanonicaTest` after adding:

```java
@Test void customerActorIsNotAStaffForeignKey() {
    OrdemServico os = Fixtures.ordem(Fixtures.cliente(Fixtures.CLIENTE_A),
        StatusOrdemServico.AGUARDANDO_APROVACAO);
    Instant now = Instant.parse("2026-09-15T12:00:00Z");
    os.aprovarExecucaoCliente(Ator.cliente(Fixtures.CLIENTE_A), now, null);
    OsHistorico h = os.getHistorico().get(os.getHistorico().size() - 1);
    assertThat(h.getAtorClienteId()).isEqualTo(Fixtures.CLIENTE_A);
    assertThat(h.getAlteradoPor()).isNull();
    assertThat(h.getOcorridoEm()).isEqualTo(now);
}
```

- [ ] **2 — Green history.** Add the approved columns/actor CHECK, nullable canonical legacy time/sequence, unique partial `(os_id, sequencia)`, aggregate `sequencia_historico`, `criado_em_utc` and `historico_completo_desde_inicio`. Map `versao` with `@Version` on order and part. Migration sets old actor to STAFF only for populated staff FK, otherwise LEGACY_UNKNOWN; unresolved old histories remain incomplete. New orders start complete and sequence starts at 1. Each transition increments the aggregate counter exactly once.
- [ ] **3 — Red/green concurrency.** Use two independent transactions/entity managers that load the same order/last-stock part before either commits. Commit the first; assert the second fails at flush/commit and rollback leaves one transition and one stock movement. Include a concurrency barrier in the test, not sleeps. Map `ObjectOptimisticLockingFailureException` and the specifically named history-sequence unique constraint to 409 `CONCURRENT_MODIFICATION`; unrelated integrity exceptions must not become 409. Keep invalid transition/stock responses 422.
- [ ] **4 — Verify clock and cutover.** `./mvnw.cmd -B '-Dtest=HistoriaCanonicaTest,ConcorrenciaAgregadosTest,OrdemServicoTest,PecaTest,GlobalExceptionHandlerTest' test`. Derive legacy compatibility timestamps from the supplied instant and an explicitly configured provenance-based zone; new synthetic cloud data uses documented UTC. A live legacy deployment with unknown provenance cannot run an invented backfill. Document old-writer drain/Recreate for this first migration in I6.
- [ ] **5 — Commit.** Stage the listed files and adapted callers/tests; `git commit -m "feat: preserve actor and concurrent order history"`.

### Task 3 (A3): Separate customer and staff trust in the API

**Files:** Modify `config/JwtService.java`, `config/JwtProperties.java`, `config/JwtAuthenticationFilter.java`, `config/SecurityUtils.java`, `config/SecurityConfig.java`; Create `security/TipoPrincipal.java`, `security/IdentidadeAutenticada.java`, `security/ValidadorToken.java`, `security/CustomerTokenValidator.java`, `security/StaffTokenValidator.java`, `security/AtorContexto.java`; Test `security/TokenTrustTest.java`, `config/JwtAuthenticationFilterTest.java`, `config/JwtServiceTest.java`.

**Interfaces:** `enum TipoPrincipal { STAFF, CUSTOMER }`; `record IdentidadeAutenticada(TipoPrincipal tipo, UUID id, Set<String> permissoes, long versaoIdentidade)`; `ValidadorToken.validar(String token): IdentidadeAutenticada`; `AtorContexto.atual(): Ator`. A3 owns test helper `support/TokenFixtures.java`: generate test RSA/HMAC keys in memory, `String customer(UUID,long,String environment,Instant expiresAt)`, `String staff(String tokenUse,String environment)`, and `String wrongAlgorithm()`; test keys never become deploy configuration.

- [ ] **1 — Red token-purpose test.** Run `TokenTrustTest` with configured fixture validators:

```java
@Test void refreshTokenCannotAuthenticateAResource() {
    String refresh = tokenFixtures.staff("refresh", "staging");
    assertThatThrownBy(() -> staffValidator.validar(refresh))
        .isInstanceOf(BadCredentialsException.class);
}
```

- [ ] **2 — Green validation.** Pin customer RS256 and staff HS256 independently, require issuer/audience/expiry/purpose/principal type, and reject unknown `kid`/external key URLs. Parse any untrusted discriminator only to choose a candidate validator; that validator rechecks every claim after signature validation. Staff current active record/roles come from `UsuarioRepository`; customer active/version comes from `ClienteRepository`. Missing/mismatched customer identity version is 401. Staff signing overwrites reserved claims after caller extras, so `extraClaims` cannot turn a refresh token into access.
- [ ] **3 — Red/green isolation matrix.** Parameterize wrong environment, wrong audience/issuer, expired/tampered token, RSA-public-key-as-HMAC attack, inactive actor, customer token with staff claims and removed/unknown kid. Ensure filter clears context after failure and emits safe 401; no bearer token in logs. Maintain `LoginResponse` field names and separate access/refresh purposes. Add explicit permission authorities from B2.
- [ ] **4 — Verify.** Run the three named test classes and existing auth/controller tests. Extend `JwtProperties` with trust configuration instead of hardcoded deployment secrets; test fixtures provide exact values. Staff must re-login after rollout, as already approved.
- [ ] **5 — Commit.** Stage named validators/config/tests; `git commit -m "feat: isolate customer and staff token trust"`.

### Task 4 (A4): Enforce owned customer reads/decisions and retire email mutation

**Files:** Modify `controller/OrdemServicoController.java`, `service/OrdemServicoService.java`, `config/SecurityConfig.java`, `dto/AcompanhamentoOsResponse.java`, `config/OpenApiConfig.java`; Create `dto/DecisaoClienteRequest.java`; Test `controller/CustomerOrderSecurityTest.java`, `service/CustomerOrderDecisionTest.java`; update existing controller/service tests and Postman examples.

**Interfaces:** `DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento decisao, String observacao)` has no identity field. Service methods `acompanhamentoDoCliente(Long numero, IdentidadeAutenticada cliente): AcompanhamentoOsResponse` and `decidirComoCliente(Long numero, IdentidadeAutenticada cliente, DecisaoClienteRequest pedido): AcompanhamentoOsResponse`. Customer projection includes their estimate, excludes internal actor/contact data.

- [ ] **1 — Red ownership test.** MockMvc uses the real filter/security configuration and a signed customer fixture token; a persisted order belongs to CLIENTE_B.

```java
@Test void anotherCustomersOrderIsHidden() throws Exception {
    assertThat(ordemPersistida.getCliente().getId()).isEqualTo(Fixtures.CLIENTE_B);
    mvc.perform(get("/ordens-servico/{numero}/acompanhamento", ordemPersistida.getNumero())
        .header("Authorization", "Bearer " + tokenFixtures.customer(
            Fixtures.CLIENTE_A, 1, "staging", Instant.now().plusSeconds(900))))
        .andExpect(status().isNotFound());
}
```

`ordemPersistida` is the real saved CLIENTE_B fixture and uses its generated number. Add the corresponding CLIENTE_B-token request returning 200 so an absent order cannot make the wrong-owner test pass accidentally. Use `/api` as servlet context in full HTTP tests; controller-only MockMvc paths omit it deliberately.
- [ ] **2 — Green ownership.** Require CUSTOMER and the exact self scope, load order by number, compare the authenticated UUID and return 404 for absent/non-owned. Delegate approval/refusal to the same transactional aggregate behavior and `Ator.cliente`. Customer cannot open/list/execute staff orders. Staff cannot use customer scopes as a substitute for their own staff routes.
- [ ] **3 — Red/green aliases/removal.** Canonical `/orcamento/decisao` and legacy `/aprovar`/`/orcamento/notificacao` invoke the same authorized use case. Legacy document input must match the authenticated customer's record but never selects identity. Remove `/email/atualizar-status` mapping and its token setting/service mutation path; assert 404 with no service invocation even when an old shared token is supplied. Remove anonymous business-route matchers; whitelist only approved auth and minimal health paths. Require existing ADMIN/MECANICO rules on remaining staff controllers.
- [ ] **4 — Verify.** Run `CustomerOrderSecurityTest,CustomerOrderDecisionTest,OrdemServicoControllerTest,OrdemServicoServiceTest` then `verify`; assert one committed approval, one stock deduction and no cross-owner mutation. Update OpenAPI/examples/re-login release notes in this commit.
- [ ] **5 — Commit.** Stage listed files, removed obsolete email mutation DTO/config only after all callers are removed, and tests; `git commit -m "feat: authorize customer order decisions"`.

### Task 5 (A5): Persist notification intent inside the business transaction

**Files:** Modify `application/port/out/NotificacaoPort.java`, `service/OrdemServicoService.java`, `adapter/out/mail/EmailNotificacaoAdapter.java`; Create `application/notificacao/StatusOrdemServicoRegistrado.java`, `adapter/out/outbox/OutboxNotificacaoAdapter.java`, `src/main/resources/db/migration/V7__criar_outbox_e_destinatario.sql`; Test `adapter/out/outbox/OutboxTransactionTest.java` and contract test.

**Interfaces:** Java record `StatusOrdemServicoRegistrado(UUID eventId, String eventType, int schemaVersion, UUID ordemId, long numero, UUID clienteId, long versaoIdentidadeCliente, long sequencia, String statusAnterior, String statusNovo, Instant ocorridoEm, String correlationId, String traceparent)` mirrors B2. `NotificacaoPort.notificarAtualizacaoStatus(StatusOrdemServicoRegistrado evento): void`. View `notificacao_destinatario_snapshot(ordem_id, numero, cliente_id, ativo, email, versao_identidade)` joins order/customer and supports CPF/CNPJ.

- [ ] **1 — Red transaction test.** Invoke a real transactional order operation, inject an outbox-insert failure, then assert the original status, stock and history count remain. A second scenario successfully commits and finds exactly one event for that new history sequence; later external failure cannot remove it.

```java
@Test void notificationInsertParticipatesInRollback() {
    long before = jdbc.queryForObject("select count(*) from outbox_eventos", Long.class);
    assertThatThrownBy(() -> transaction.executeWithoutResult(s -> {
        notificacaoPort.notificarAtualizacaoStatus(evento);
        throw new IllegalStateException("controlled rollback");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("select count(*) from outbox_eventos", Long.class))
        .isEqualTo(before);
}
```

`evento` is deserialized from B2 with registered Jackson JavaTimeModule; real order/history fixtures satisfy the event FKs in the full service scenario.
- [ ] **2 — Green schema.** Define `outbox_eventos(event_id UUID PK, os_id UUID FK, sequencia BIGINT, event_type TEXT, schema_version INT, payload JSONB, estado TEXT, tentativas INT, disponivel_em TIMESTAMPTZ, criado_em TIMESTAMPTZ, publicado_em TIMESTAMPTZ, queue_message_id TEXT, ultimo_erro_codigo TEXT)`. Enforce unique `(os_id,sequencia,event_type)`, allowed states PENDING/PUBLISHED/BLOCKED/SKIPPED, payload version/size and due-publication index. Add `outbox_recuperacoes(id UUID PK,event_id UUID FK,acao TEXT,operador_ref TEXT,ocorrido_em TIMESTAMPTZ,motivo TEXT)` for inspected retry/skip audit; no raw secrets/contact information.
- [ ] **3 — Green adapter.** Insert via JdbcTemplate participating in the existing transaction. Build the immutable event after trusted aggregate/history values and generated order number are available; flush before snapshot construction when required. Produce initial RECEBIDA and each actual transition. Replace synchronous SMTP selection with outbox delivery; retain MailHog only as a local consumer adapter if used, never a cloud transaction side effect. No JPA graph is serialized or passed into FUN.
- [ ] **4 — Verify.** Run `OutboxTransactionTest,Phase3ContractTest,OrdemServicoServiceTest`; add rollback tests at stock/history/outbox boundaries and unique-event retry. Validate the recipient view with inactive/contact-version changes and CNPJ fixtures; its GRANT test follows I3/I6.
- [ ] **5 — Commit.** Stage listed files/tests; `git commit -m "feat: persist transactional notification intent"`.

### Task 6 (A6): Publish FIFO events without overtaking predecessors

**Files:** Create `application/notificacao/PublicadorFila.java`, `adapter/out/outbox/OutboxPublisher.java`, `adapter/out/sqs/SqsPublicadorFila.java`, `config/OutboxConfiguration.java`, `scripts/database/purge-published-outbox.sql`; Test `adapter/out/outbox/OutboxPublisherTest.java`, `adapter/out/sqs/SqsPublicadorFilaTest.java`; Modify APP `pom.xml` with the B1-pinned SDK SQS dependency.

**Interfaces:** `PublicadorFila.enviar(StatusOrdemServicoRegistrado evento): String` returns SQS message ID. `OutboxPublisher.publicarProximo(): boolean` processes at most one event. Constructor consumes `JdbcTemplate`, `TransactionTemplate`, `PublicadorFila`, `Clock`; backoff randomness is injected through `LongUnaryOperator` for deterministic tests.

- [ ] **1 — Red ordering test.** In real PostgreSQL lock order A's first pending row on connection 1. Connection 2 calls publisher with order A's second event and order B's first event present. Assert B publishes, A's second does not. A BLOCKED predecessor also prevents A's later publication.
- [ ] **2 — Green selection/send.** Execute the following selection inside a separate short publisher transaction; keep its row lock until the SQS attempt and state update finish, without taking order/stock locks:

```sql
SELECT e.* FROM outbox_eventos e
WHERE e.estado = 'PENDING' AND e.disponivel_em <= :agora
AND NOT EXISTS (
 SELECT 1 FROM outbox_eventos anterior
 WHERE anterior.os_id=e.os_id AND anterior.sequencia<e.sequencia
 AND anterior.estado IN ('PENDING','BLOCKED')
)
ORDER BY e.disponivel_em,e.criado_em
FOR UPDATE OF e SKIP LOCKED LIMIT 1;
```

```java
SendMessageResponse result = sqs.sendMessage(b -> b.queueUrl(queueUrl)
    .messageGroupId(evento.ordemId().toString())
    .messageDeduplicationId(evento.eventId().toString())
    .messageBody(mapper.writeValueAsString(evento)));
```

Configure SDK API-call timeout 2 seconds and one total attempt. Schedule one bounded iteration every 5 seconds per pod. SQS ACK → update PUBLISHED/message ID in the same DB transaction.
- [ ] **3 — Red/green failure cases.** SQS failure increments persisted attempt and schedules jittered delay from 5 seconds to 5 minutes; attempt 12 becomes BLOCKED. Database failure after SQS ACK rolls back, retaining the stable event ID for retry. DB failure before persistence backs off locally without spinning; do not continue SQL on an aborted transaction. Tests assert exact attempts/state/ID and no business rollback after commit.
- [ ] **4 — Verify.** Run both named tests and `verify`. Assert payload ≤8 KiB, safe error codes, no ordering bypass on delayed predecessor, and max five Hikari connections includes publisher use. Retention SQL selects only PUBLISHED rows older than 7 days; never purge PENDING/BLOCKED or delete referenced recovery audit implicitly. Provide preview/export before its maintenance deletion path, and test the age/state predicates with real PostgreSQL. Inspected BLOCKED retry/skip uses the R4 recovery command and audit table; no automatic skip.
- [ ] **5 — Commit.** Stage publisher/SDK/config/test/POM changes; `git commit -m "feat: publish ordered notification events"`.

### Task 7 (A7): Implement canonical SQL business reports

**Files:** Create `application/relatorio/RelatoriosPort.java`, `application/relatorio/RelatorioPeriodo.java`, `application/relatorio/DuracaoStatus.java`, `application/relatorio/StatusAtual.java`, `adapter/out/relatorio/JdbcRelatoriosAdapter.java`, `controller/RelatoriosAdminController.java`, `src/main/resources/queries/relatorio-periodo.sql`, `src/main/resources/db/migration/V8__indexar_relatorios.sql`; Test `adapter/out/relatorio/RelatorioPeriodoTest.java`, `controller/RelatoriosAdminControllerTest.java`.

**Interfaces:** `RelatoriosPort.consultar(LocalDate inicio,LocalDate fimExclusive,ZoneId zona): RelatorioPeriodo`; `statusAtual(Instant agora): List<StatusAtual>`. `RelatorioPeriodo(long criadas,long elegiveis,long excluidas,Map<StatusOrdemServico,DuracaoStatus> duracoes)`; `DuracaoStatus(BigDecimal totalSegundos,long amostras)`; `StatusAtual(StatusOrdemServico status,long quantidade,BigDecimal idadeMaximaSegundos,long amostrasIdade,long idadesDesconhecidas)`. Controller `GET /api/admin/relatorios/ordens?inicio=2026-09-15&fimExclusive=2026-09-16` uses fixed business zone America/Sao_Paulo and validates `fimExclusive > inicio`.

- [ ] **1 — Red cohort fixture.** Persist delivered order A with repeated diagnosis totaling 35 minutes, execution 60 and finalization wait 30; B with 25/40/10. Persist an unfinished order and one incomplete legacy history. Assert the delivered-cohort means are 30/50/20, eligible=2 and incomplete delivered records count as excluded.

```java
assertThat(report.duracoes().get(StatusOrdemServico.EM_DIAGNOSTICO).totalSegundos())
    .isEqualByComparingTo("3600");
assertThat(report.duracoes().get(StatusOrdemServico.EM_DIAGNOSTICO).amostras())
    .isEqualTo(2);
```

- [ ] **2 — Green SQL projection.** Use `LEAD(ocorrido_em) OVER (PARTITION BY os_id ORDER BY sequencia)` and its next status/sequence to form completed intervals. Validate sequence continuity, status continuity and nonnegative duration across the full history. Select ENTREGUE histories in `[inicio.atStartOfDay(zona), fimExclusive.atStartOfDay(zona))`, with complete verified histories. Sum intervals per order/status, then sum totals/count orders for each status. Count created orders independently by `criado_em_utc`. Do not filter history to the report dates before calculating an order's whole lifecycle.
- [ ] **3 — Red/green boundary cases.** Empty cohort exports zero total/count and API mean N/A; refusal adds all diagnosis intervals; midnight respects business-zone conversion; tied/broken/unknown histories are excluded; active status age uses latest canonical transition or reports unknown. ADMIN only. Read-only transaction and 2-second statement timeout; release connection before exporter/network calls in R2.
- [ ] **4 — Verify.** Run both named tests, record `EXPLAIN (ANALYZE, BUFFERS)` on representative synthetic data and add only justified indexes in V8, preserving existing uniqueness/FKs. Confirm 30/50/20-minute equivalence through the HTTP report endpoint. Existing `MetricasService` compatibility endpoint can remain but is not the new per-status report.
- [ ] **5 — Commit.** Stage reporting/SQL/index/controller/tests; `git commit -m "feat: report canonical order lifecycle metrics"`.
