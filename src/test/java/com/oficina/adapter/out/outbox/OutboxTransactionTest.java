package com.oficina.adapter.out.outbox;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oficina.dto.CriarOrdemServicoRequest;
import com.oficina.dto.DecisaoClienteRequest;
import com.oficina.dto.DecisaoOrcamentoRequest;
import com.oficina.entity.*;
import com.oficina.repository.*;
import com.oficina.security.*;
import com.oficina.service.*;
import com.oficina.support.Fixtures;
import com.oficina.support.PostgresIntegrationSupport;
import com.oficina.config.CorrelationFilter;
import com.oficina.application.observability.OrderTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@Import({OrdemServicoService.class, OutboxNotificacaoAdapter.class, com.oficina.adapter.out.mail.EmailNotificacaoAdapter.class,
        OutboxTransactionTest.Configuration.class})
@org.springframework.test.context.ActiveProfiles("local-mailhog")
@TestPropertySource(properties = "oficina.historico.zona-compatibilidade=UTC")
class OutboxTransactionTest extends PostgresIntegrationSupport {
    @TestConfiguration static class Configuration {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
    }
    @Autowired OrdemServicoService service;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired PecaRepository pecas;
    @Autowired OrdemServicoRepository ordens;
    @MockBean ClienteService clienteService;
    @MockBean VeiculoService veiculoService;
    @MockBean ServicoService servicoService;
    @MockBean PecaService pecaService;
    @Autowired NotificacaoPort notifications;
    @Autowired ObjectMapper mapper;
    @Autowired com.oficina.adapter.out.mail.EmailNotificacaoAdapter localMail;
    @MockBean org.springframework.mail.javamail.JavaMailSender mailSender;
    @MockBean OrderTelemetry telemetry;
    OrdemServico order;
    Cliente customer;

    @BeforeEach void fixture() {
        customer = clientes.findByDocumento("39053344705").orElseGet(() -> clientes.save(Fixtures.cliente(null)));
        order = transaction.execute(tx -> {
            var vehicle = veiculos.save(Veiculo.builder().cliente(customer).placa(UUID.randomUUID().toString().substring(0, 8))
                    .marca("Fiat").modelo("Uno").ano(2020).ativo(true).build());
            var part = pecas.save(Peca.builder().codigo(UUID.randomUUID().toString()).nome("Filtro")
                    .quantidadeEstoque(10).quantidadeMinima(1).valorUnitario(BigDecimal.TEN).unidadeMedida("UN").ativo(true).build());
            var entity = Fixtures.ordem(customer, StatusOrdemServico.AGUARDANDO_APROVACAO);
            entity.setVeiculo(vehicle);
            entity.adicionarPeca(OsPecaItem.builder().peca(part).quantidade(2).valorUnitario(BigDecimal.TEN)
                    .valorTotal(new BigDecimal("20.00")).build());
            return ordens.saveAndFlush(entity);
        });
    }

    @Test void committedApprovalHasExactlyOneNotificationIntent() throws Exception {
        approve();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos WHERE os_id=? AND sequencia=4",
                Integer.class, order.getId())).isEqualTo(1);
        assertState("EM_EXECUCAO", 8, 4, 1);
        var event = persistedEvent();
        assertThat(event.ordemId()).isEqualTo(order.getId());
        assertThat(event.numero()).isEqualTo(order.getNumero());
        assertThat(event.clienteId()).isEqualTo(customer.getId());
        assertThat(event.versaoIdentidadeCliente()).isEqualTo(customer.getVersaoIdentidade());
        assertThat(event.statusAnterior()).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(event.statusNovo()).isEqualTo("EM_EXECUCAO");
        assertThat(event.ocorridoEm()).isEqualTo(jdbc.queryForObject(
                "SELECT ocorrido_em FROM os_historico WHERE os_id=? AND sequencia=4", java.sql.Timestamp.class,
                order.getId()).toInstant());
        assertThat(jdbc.queryForMap("SELECT estado, tentativas, publicado_em FROM outbox_eventos WHERE os_id=?",
                order.getId())).containsEntry("estado", "PENDING").containsEntry("tentativas", 0).containsEntry("publicado_em", null);
        assertThat(mapper.writeValueAsString(event)).doesNotContain("private@example.invalid", customer.getDocumento());
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            jdbc.update("UPDATE outbox_eventos SET estado='PUBLISHED' WHERE event_id=?", event.eventId());
            throw new IllegalStateException("External publisher unavailable after business commit");
        })).isInstanceOf(IllegalStateException.class);
        assertState("EM_EXECUCAO", 8, 4, 1);
        assertThat(jdbc.queryForObject("SELECT estado FROM outbox_eventos WHERE event_id=?", String.class,
                event.eventId())).isEqualTo("PENDING");
    }

    @Test void accepted_uuid_correlation_header_survives_real_status_mutation_into_outbox() throws Exception {
        String correlation = "00000000-0000-0000-0000-0000000004a1";
        var request = new MockHttpServletRequest(); request.addHeader("X-Correlation-Id", correlation);
        var response = new MockHttpServletResponse();
        new CorrelationFilter().doFilter(request, response, (ignoredRequest, ignoredResponse) -> approve());
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(correlation);
        assertThat(persistedEvent().correlationId()).isEqualTo(correlation);
        verify(telemetry).commandCompleted("order_decision", "accepted");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pecas", "os_historico", "outbox_eventos"})
    void databaseFailureRollsBackOrderStockHistoryAndOutbox(String table) {
        // Names are a fixed parameterized-test whitelist; no request data reaches DDL.
        jdbc.execute("CREATE FUNCTION fail_a5_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'CONTROLLED_A5_FAILURE'; END $$");
        jdbc.execute("CREATE TRIGGER fail_a5 BEFORE INSERT OR UPDATE ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION fail_a5_insert()");
        try {
            assertThatThrownBy(this::approve).isInstanceOf(RuntimeException.class)
                    .hasStackTraceContaining("CONTROLLED_A5_FAILURE");
            assertState("AGUARDANDO_APROVACAO", 10, 3, 0);
            verify(telemetry).commandCompleted("order_decision", "technical-failure");
        } finally {
            jdbc.execute("DROP TRIGGER fail_a5 ON " + table);
            jdbc.execute("DROP FUNCTION fail_a5_insert()");
        }
        approve();
        assertState("EM_EXECUCAO", 8, 4, 1);
    }

    @Test void duplicateDeliveryIntentAndBusinessReplayCannotDuplicateHistoryOrStock() throws Exception {
        approve();
        var event = persistedEvent();
        var duplicate = new StatusOrdemServicoRegistrado(UUID.randomUUID(), event.eventType(), 1,
                event.ordemId(), event.numero(), event.clienteId(), event.versaoIdentidadeCliente(), event.sequencia(),
                event.statusAnterior(), event.statusNovo(), event.ocorridoEm(), event.correlationId(), event.traceparent());
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> notifications.notificarAtualizacaoStatus(duplicate)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(this::approve).isInstanceOf(com.oficina.exception.BusinessRuleException.class);
        assertState("EM_EXECUCAO", 8, 4, 1);
    }

    @Test void fixtureContractInsertParticipatesInExplicitRollbackAndRequiresTransaction() throws Exception {
        var fixture = mapper.readValue(Path.of("contracts/phase3-v1/status-event.json").toFile(), StatusOrdemServicoRegistrado.class);
        // Keep the frozen contract shape and bind it to real generated order/customer/history references.
        var event = new StatusOrdemServicoRegistrado(fixture.eventId(), fixture.eventType(), fixture.schemaVersion(),
                order.getId(), order.getNumero(), customer.getId(), customer.getVersaoIdentidade(), 1,
                fixture.statusAnterior(), fixture.statusNovo(), fixture.ocorridoEm(), fixture.correlationId(), fixture.traceparent());
        assertThatThrownBy(() -> notifications.notificarAtualizacaoStatus(event))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            notifications.notificarAtualizacaoStatus(event);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos WHERE os_id=?", Integer.class,
                    order.getId())).isEqualTo(1);
            throw new IllegalStateException("Controlled rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertState("AGUARDANDO_APROVACAO", 10, 3, 0);
    }

    @Test void localDownstreamSmtpCannotRunInTransactionAndFailureCannotEraseCommittedIntent() throws Exception {
        approve();
        org.mockito.Mockito.verifyNoInteractions(mailSender);
        var event = persistedEvent();
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> localMail.enviar(event, customer.getEmail())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        org.mockito.Mockito.verifyNoInteractions(mailSender);
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("CONTROLLED_SMTP_FAILURE"))
                .when(mailSender).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        assertThatThrownBy(() -> localMail.enviar(event, customer.getEmail()))
                .isInstanceOf(org.springframework.mail.MailSendException.class);
        assertState("EM_EXECUCAO", 8, 4, 1);
    }

    @Test void creationAndEveryActualTransitionProduceCanonicalEvents() throws Exception {
        when(clienteService.obterEntidadeAtivaPorDocumento(customer.getDocumento())).thenReturn(customer);
        when(veiculoService.obterAtivoPorPlaca(order.getVeiculo().getPlaca())).thenReturn(order.getVeiculo());
        var created = service.criar(new CriarOrdemServicoRequest(customer.getDocumento(), order.getVeiculo().getPlaca(),
                null, null, null, List.of(), List.of(), "private@example.invalid"));
        var initial = mapper.readValue(jdbc.queryForObject("SELECT payload::text FROM outbox_eventos WHERE os_id=?",
                String.class, created.id()), StatusOrdemServicoRegistrado.class);
        assertThat(initial.numero()).isEqualTo(created.numero()).isPositive();
        assertThat(initial.statusAnterior()).isNull();
        assertThat(initial.statusNovo()).isEqualTo("RECEBIDA");
        assertThat(initial.sequencia()).isEqualTo(1);
        service.iniciarDiagnostico(created.id(), null);
        service.enviarOrcamento(created.id(), null);
        var identity = new IdentidadeAutenticada(TipoPrincipal.CUSTOMER, customer.getId(), Set.of("SCOPE_orders:decide:self"), 1);
        service.decidirComoCliente(created.numero(), identity,
                new DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.RECUSADO, null));
        service.enviarOrcamento(created.id(), null);
        service.decidirComoCliente(created.numero(), identity,
                new DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO, null));
        service.finalizar(created.id(), null);
        service.registrarEntrega(created.id(), null);
        assertThat(jdbc.queryForList("SELECT sequencia FROM outbox_eventos WHERE os_id=? ORDER BY sequencia",
                Long.class, created.id())).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_eventos e JOIN os_historico h ON h.os_id=e.os_id AND h.sequencia=e.sequencia
                WHERE e.os_id=? AND e.payload->>'statusNovo'=h.status_novo::text
                    AND (e.payload->>'statusAnterior') IS NOT DISTINCT FROM h.status_anterior::text
                    AND (e.payload->>'ocorridoEm')::timestamptz=h.ocorrido_em
                """, Integer.class, created.id())).isEqualTo(8);
    }

    @Test void recipientSnapshotIncludesCpfAndCnpjAndReflectsContactVersionAndInactiveState() throws Exception {
        var corporate = clientes.findByDocumento("11222333000181").orElseGet(() -> clientes.save(Cliente.builder()
                .nome("Empresa").tipoDocumento(TipoDocumento.CNPJ).documento("11222333000181")
                .email("company@example.invalid").telefone("11999999999").ativo(true).build()));
        jdbc.update("UPDATE ordens_servico SET cliente_id=? WHERE id=?", corporate.getId(), order.getId());
        var before = jdbc.queryForMap("SELECT * FROM notificacao_destinatario_snapshot WHERE ordem_id=?", order.getId());
        assertThat(before).containsOnlyKeys("ordem_id", "numero", "cliente_id", "ativo", "email", "versao_identidade")
                .containsEntry("cliente_id", corporate.getId()).containsEntry("ativo", true).containsEntry("email", "company@example.invalid");
        var cpfCustomer = customer;
        customer = corporate;
        approve();
        var event = persistedEvent();
        assertThat(event.clienteId()).isEqualTo(corporate.getId());
        assertThat(event.versaoIdentidadeCliente()).isEqualTo(corporate.getVersaoIdentidade());
        assertThat(mapper.writeValueAsString(event)).doesNotContain(corporate.getDocumento(), corporate.getEmail());
        jdbc.update("UPDATE clientes SET email='updated@example.invalid', ativo=false, versao_identidade=versao_identidade+1 WHERE id=?",
                corporate.getId());
        var after = jdbc.queryForMap("SELECT * FROM notificacao_destinatario_snapshot WHERE ordem_id=?", order.getId());
        assertThat(after).containsEntry("ativo", false).containsEntry("email", "updated@example.invalid")
                .containsEntry("versao_identidade", ((Long) before.get("versao_identidade")) + 1);
        assertThat(persistedEvent()).isEqualTo(event);
        customer = cpfCustomer;
        jdbc.update("UPDATE ordens_servico SET cliente_id=? WHERE id=?", customer.getId(), order.getId());
        assertThat(jdbc.queryForObject("SELECT cliente_id FROM notificacao_destinatario_snapshot WHERE ordem_id=?",
                UUID.class, order.getId())).isEqualTo(customer.getId());
    }

    @Test void databaseRejectsInvalidStateVersionOversizeAndContactPayload() {
        approve();
        for (String assignment : List.of("estado='UNKNOWN'", "schema_version=2", "payload=jsonb_set(payload,'{schemaVersion}','2')",
                "payload=payload || '{\"email\":\"forbidden@example.invalid\"}'::jsonb",
                "payload=jsonb_set(payload,'{correlationId}',to_jsonb(repeat('x',8193)))", "payload='{}'::jsonb")) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> jdbc.update(
                    "UPDATE outbox_eventos SET " + assignment + " WHERE os_id=?", order.getId())))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertState("EM_EXECUCAO", 8, 4, 1);
    }

    StatusOrdemServicoRegistrado persistedEvent() throws Exception {
        return mapper.readValue(jdbc.queryForObject("SELECT payload::text FROM outbox_eventos WHERE os_id=?",
                String.class, order.getId()), StatusOrdemServicoRegistrado.class);
    }

    void assertState(String status, int stock, int history, int events) {
        assertThat(jdbc.queryForObject("SELECT status::text FROM ordens_servico WHERE id=?", String.class, order.getId())).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT p.quantidade_estoque FROM pecas p JOIN os_pecas op ON op.peca_id=p.id WHERE op.os_id=?",
                Integer.class, order.getId())).isEqualTo(stock);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM os_historico WHERE os_id=?", Integer.class, order.getId())).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos WHERE os_id=?", Integer.class, order.getId())).isEqualTo(events);
    }

    void approve() {
        service.decidirComoCliente(order.getNumero(), new IdentidadeAutenticada(TipoPrincipal.CUSTOMER,
                customer.getId(), Set.of("SCOPE_orders:decide:self"), customer.getVersaoIdentidade()),
                new DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO, "private@example.invalid"));
    }
}
