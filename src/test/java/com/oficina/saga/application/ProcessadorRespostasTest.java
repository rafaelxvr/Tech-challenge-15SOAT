package com.oficina.saga.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.repository.*;
import com.oficina.saga.adapter.out.os.OsDaSagaAdapter;
import com.oficina.saga.adapter.out.persistence.JdbcInbox;
import com.oficina.saga.adapter.out.persistence.JdbcSagaOutbox;
import com.oficina.saga.adapter.out.persistence.OsSagaRepository;
import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.application.mensagem.MensagemInvalidaException;
import com.oficina.saga.config.SagaConfiguration;
import com.oficina.saga.domain.EstadoSaga;
import com.oficina.service.RegistroTransicaoOs;
import com.oficina.support.PostgresIntegrationSupport;
import com.oficina.support.SagaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({ProcessadorRespostas.class, JdbcInbox.class, CicloOsSagaService.class, ExecutorEfeitos.class,
        RoteadorRespostas.class, OsDaSagaAdapter.class, RegistroTransicaoOs.class, JdbcSagaOutbox.class,
        MapeadorMensagens.class, SagaConfiguration.class, ProcessadorRespostasTest.Relogio.class})
@TestPropertySource(properties = {"oficina.historico.zona-compatibilidade=UTC",
        "oficina.saga.filial-padrao=MATRIZ", "oficina.saga.prazos.diagnostico=3d",
        "oficina.saga.prazos.resposta-automatica=5m", "oficina.saga.prazos.decisao=7d",
        "oficina.saga.prazos.pagamento=30m", "oficina.saga.prazos.compensacao=5m",
        "oficina.saga.prazos.max-tentativas-compensacao=5"})
class ProcessadorRespostasTest extends PostgresIntegrationSupport {
    @TestConfiguration static class Relogio {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired ProcessadorRespostas processador;
    @Autowired CicloOsSagaService ciclo;
    @Autowired OsSagaRepository sagas;
    @Autowired MapeadorMensagens mapeador;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired OrdemServicoRepository ordens;
    @Autowired PecaRepository pecas;
    @Autowired ServicoRepository servicos;
    @MockBean NotificacaoPort notificacoes;
    UUID os;

    @BeforeEach void iniciar() {
        jdbc.update("DELETE FROM saga_inbox");
        jdbc.update("DELETE FROM saga_outbox");
        jdbc.update("DELETE FROM os_saga_passo");
        jdbc.update("DELETE FROM os_saga");
        var cliente = SagaFixtures.cliente(clientes);
        os = SagaFixtures.ordem(transaction, ordens, veiculos, pecas, servicos, cliente,
                SagaFixtures.veiculo(jdbc, cliente.getId()), StatusOrdemServico.RECEBIDA,
                Map.of(), Map.of(SagaFixtures.servico(jdbc, "80.00"), 1));
        transaction.executeWithoutResult(st -> ciclo.iniciar(os));
    }

    UUID ultimoComando() {
        return jdbc.queryForObject("SELECT message_id FROM saga_outbox WHERE os_id=? ORDER BY sequencia DESC LIMIT 1",
                UUID.class, os);
    }

    String resposta(UUID messageId, String tipo, UUID causa, ObjectNode payload) {
        var saga = sagas.findByOsId(os).orElseThrow();
        return mapeador.escrever(new Envelope(messageId, Envelope.Kind.REPLY, tipo, 1, saga.getId(), os, 1, causa,
                Instant.parse("2026-09-15T12:01:00Z"), UUID.randomUUID().toString(), null, payload));
    }

    String respostaComRastreio(String tipo, UUID causa, String correlationId, String traceparent) {
        var saga = sagas.findByOsId(os).orElseThrow();
        return mapeador.escrever(new Envelope(UUID.randomUUID(), Envelope.Kind.REPLY, tipo, 1, saga.getId(), os, 1, causa,
                Instant.parse("2026-09-15T12:01:00Z"), correlationId, traceparent, JsonNodeFactory.instance.objectNode()));
    }

    Map<String, Object> rastreioDoUltimoComando() {
        return jdbc.queryForMap("SELECT envelope->>'correlationId' AS correlation, envelope->>'traceparent' AS trace "
                + "FROM saga_outbox WHERE os_id=? ORDER BY sequencia DESC LIMIT 1", os);
    }

    EstadoSaga estado() { return sagas.findByOsId(os).orElseThrow().getEstado(); }

    @Test void respostaValidaAvancaSagaEEmiteProximoComando() {
        processador.processar(resposta(UUID.randomUUID(), "DiagnosticoConcluido", ultimoComando(),
                JsonNodeFactory.instance.objectNode()));
        assertThat(estado()).isEqualTo(EstadoSaga.AGUARDANDO_ORCAMENTO);
        assertThat(jdbc.queryForObject("SELECT tipo FROM saga_outbox WHERE message_id=?", String.class, ultimoComando()))
                .isEqualTo("GerarOrcamento");
    }

    @Test void respostaDuplicadaEIgnorada() {
        UUID id = UUID.randomUUID();
        String corpo = resposta(id, "DiagnosticoConcluido", ultimoComando(), JsonNodeFactory.instance.objectNode());
        processador.processar(corpo);
        processador.processar(corpo);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_outbox WHERE os_id=?", Integer.class, os)).isEqualTo(2);
        assertThat(estado()).isEqualTo(EstadoSaga.AGUARDANDO_ORCAMENTO);
    }

    @Test void respostaAtrasadaNaoAlteraSaga() {
        UUID diagnostico = ultimoComando();
        processador.processar(resposta(UUID.randomUUID(), "DiagnosticoConcluido", diagnostico,
                JsonNodeFactory.instance.objectNode()));
        processador.processar(resposta(UUID.randomUUID(), "DiagnosticoConcluido", diagnostico,
                JsonNodeFactory.instance.objectNode()));
        processador.processar(resposta(UUID.randomUUID(), "OrcamentoGerado", UUID.randomUUID(),
                JsonNodeFactory.instance.objectNode().put("orcamentoId", UUID.randomUUID().toString())
                        .put("total", new BigDecimal("80.00"))));
        assertThat(estado()).isEqualTo(EstadoSaga.AGUARDANDO_ORCAMENTO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_outbox WHERE os_id=?", Integer.class, os)).isEqualTo(2);
    }

    @Test void orcamentoGeradoAtualizaOsParaAguardandoAprovacao() {
        processador.processar(resposta(UUID.randomUUID(), "DiagnosticoConcluido", ultimoComando(),
                JsonNodeFactory.instance.objectNode()));
        processador.processar(resposta(UUID.randomUUID(), "OrcamentoGerado", ultimoComando(),
                JsonNodeFactory.instance.objectNode().put("orcamentoId", UUID.randomUUID().toString())
                        .put("total", new BigDecimal("80.00"))));
        assertThat(estado()).isEqualTo(EstadoSaga.AGUARDANDO_DECISAO);
        assertThat(jdbc.queryForObject("SELECT status::text FROM ordens_servico WHERE id=?", String.class, os))
                .isEqualTo("AGUARDANDO_APROVACAO");
    }

    @Test void corpoInvalidoOuComandoSaoRejeitadosSemGravarInbox() {
        assertThatThrownBy(() -> processador.processar("{}")).isInstanceOf(MensagemInvalidaException.class);
        var saga = sagas.findByOsId(os).orElseThrow();
        String comando = mapeador.escrever(new Envelope(UUID.randomUUID(), Envelope.Kind.COMMAND, "GerarOrcamento", 1,
                saga.getId(), os, 2, null, Instant.now(), UUID.randomUUID().toString(), null,
                JsonNodeFactory.instance.objectNode()));
        assertThatThrownBy(() -> processador.processar(comando)).isInstanceOf(MensagemInvalidaException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_inbox", Integer.class)).isZero();
    }

    @Test void rastreioValidoDaRespostaSegueNoProximoComando() {
        String correlation = UUID.randomUUID().toString();
        String trace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        processador.processar(respostaComRastreio("DiagnosticoConcluido", ultimoComando(), correlation, trace));
        assertThat(rastreioDoUltimoComando()).containsEntry("correlation", correlation).containsEntry("trace", trace);
    }

    @Test void rastreioInvalidoDaRespostaNaoChegaAoProximoComando() {
        processador.processar(respostaComRastreio("DiagnosticoConcluido", ultimoComando(),
                "nao-e-uuid\"}", "01-invalido"));
        var rastreio = rastreioDoUltimoComando();
        assertThat((String) rastreio.get("correlation"))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(rastreio.get("trace")).isNull();
        assertThat(org.slf4j.MDC.get("correlation_id")).isNull();
        assertThat(org.slf4j.MDC.get("traceparent")).isNull();
    }

    @Test void tipoDesconhecidoEConsumidoSemEfeito() {
        processador.processar(resposta(UUID.randomUUID(), "EventoFuturo", ultimoComando(),
                JsonNodeFactory.instance.objectNode()));
        assertThat(estado()).isEqualTo(EstadoSaga.AGUARDANDO_DIAGNOSTICO);
    }
}
