package com.oficina.saga.adapter.out.messaging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.oficina.adapter.out.outbox.BackoffPublicacao;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.repository.*;
import com.oficina.saga.adapter.out.persistence.JdbcSagaOutbox;
import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.application.port.EnviadorMensagens;
import com.oficina.saga.application.rastreio.PropagadorTraco;
import com.oficina.saga.domain.Destino;
import com.oficina.support.PostgresIntegrationSupport;
import com.oficina.support.SagaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Import({JdbcSagaOutbox.class, MapeadorMensagens.class})
class SagaOutboxPublisherTest extends PostgresIntegrationSupport {
    static final Instant T = Instant.parse("2026-09-15T12:00:00Z");
    @Autowired JdbcSagaOutbox outbox;
    @MockBean PropagadorTraco propagador;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired OrdemServicoRepository ordens;
    @Autowired PecaRepository pecas;
    @Autowired ServicoRepository servicos;
    final List<String> enviados = new ArrayList<>();
    // saga_outbox.disponivel_em defaults to the database clock, so the publisher clock must be past it.
    final Clock clock = Clock.fixed(Instant.now().plusSeconds(60), ZoneOffset.UTC);

    @BeforeEach void limpar() { jdbc.update("DELETE FROM saga_outbox"); }

    UUID ordem() {
        var cliente = SagaFixtures.cliente(clientes);
        return SagaFixtures.ordem(transaction, ordens, veiculos, pecas, servicos, cliente,
                SagaFixtures.veiculo(jdbc, cliente.getId()), StatusOrdemServico.RECEBIDA, Map.of(), Map.of());
    }

    Envelope comando(UUID osId, String tipo) {
        return new Envelope(UUID.randomUUID(), Envelope.Kind.COMMAND, tipo, 1, UUID.randomUUID(), osId, 1, null, T,
                UUID.randomUUID().toString(), null, JsonNodeFactory.instance.objectNode());
    }

    SagaOutboxPublisher publisher(EnviadorMensagens enviador) {
        return new SagaOutboxPublisher(jdbc, transaction, enviador, clock, new BackoffPublicacao(b -> 0));
    }

    @Test void publicaNaOrdemDaOsEGravaConfirmacao() {
        UUID os = ordem();
        var primeiro = comando(os, "IniciarDiagnostico");
        var segundo = comando(os, "GerarOrcamento");
        transaction.executeWithoutResult(st -> {
            outbox.registrar(Destino.EXECUCAO, primeiro);
            outbox.registrar(Destino.BILLING, segundo);
        });
        var p = publisher((destino, osId, messageId, corpo, traco) -> { enviados.add(destino + ":" + messageId); return "sqs-" + messageId; });
        assertThat(p.publicarProximo()).isTrue();
        assertThat(p.publicarProximo()).isTrue();
        assertThat(p.publicarProximo()).isFalse();
        assertThat(enviados).containsExactly("EXECUCAO:" + primeiro.messageId(), "BILLING:" + segundo.messageId());
        assertThat(jdbc.queryForMap("SELECT estado, queue_message_id FROM saga_outbox WHERE message_id=?", primeiro.messageId()))
                .containsEntry("estado", "PUBLISHED").containsEntry("queue_message_id", "sqs-" + primeiro.messageId());
    }

    @Test void falhaAtrasaEImpedeUltrapassagemNaMesmaOs() {
        UUID os = ordem();
        var primeiro = comando(os, "IniciarDiagnostico");
        var segundo = comando(os, "GerarOrcamento");
        transaction.executeWithoutResult(st -> {
            outbox.registrar(Destino.EXECUCAO, primeiro);
            outbox.registrar(Destino.BILLING, segundo);
        });
        var p = publisher((destino, osId, messageId, corpo, traco) -> { throw new IllegalStateException("token=secret"); });
        assertThat(p.publicarProximo()).isTrue();
        assertThat(jdbc.queryForMap("SELECT estado, tentativas, ultimo_erro_codigo FROM saga_outbox WHERE message_id=?",
                primeiro.messageId())).containsEntry("estado", "PENDING").containsEntry("tentativas", 1)
                .containsEntry("ultimo_erro_codigo", "QUEUE_SEND_FAILED");
        assertThat(p.publicarProximo()).isFalse();
    }

    @Test void envelopeAcimaDoLimiteERecusadoNaEscrita() {
        UUID os = ordem();
        var grande = new Envelope(UUID.randomUUID(), Envelope.Kind.COMMAND, "GerarOrcamento", 1, UUID.randomUUID(), os, 2,
                null, T, UUID.randomUUID().toString(), null,
                JsonNodeFactory.instance.objectNode().put("x", "a".repeat(Envelope.MAX_BYTES)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.executeWithoutResult(st ->
                outbox.registrar(Destino.BILLING, grande))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void carrierCapturadoNaTransacaoProdutoraChegaAoEnvioSemPoluirOCorpo() {
        var carrier = new LinkedHashMap<String, String>();
        carrier.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        carrier.put("tracestate", "123@nr=0-0-1-2-3");
        org.mockito.Mockito.when(propagador.capturar()).thenReturn(carrier);
        UUID os = ordem();
        var cmd = comando(os, "IniciarDiagnostico");
        transaction.executeWithoutResult(st -> {
            outbox.registrar(Destino.EXECUCAO, cmd);
            // Captured while the producing transaction is still open.
            org.mockito.Mockito.verify(propagador).capturar();
        });
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(envelope, '_carrierRastreio') FROM saga_outbox WHERE message_id=?",
                Boolean.class, cmd.messageId())).isTrue();
        var corpos = new ArrayList<String>();
        var carriers = new ArrayList<Map<String, String>>();
        var p = publisher((destino, osId, messageId, corpo, traco) -> { corpos.add(corpo); carriers.add(traco); return "ack"; });
        assertThat(p.publicarProximo()).isTrue();
        assertThat(carriers).containsExactly(carrier);
        assertThat(corpos.get(0)).doesNotContain("_carrierRastreio").doesNotContain("tracestate");
    }

    @Test void semCarrierOEnvioSegueComCarrierVazio() {
        org.mockito.Mockito.when(propagador.capturar()).thenReturn(Map.of());
        UUID os = ordem();
        var cmd = comando(os, "IniciarDiagnostico");
        transaction.executeWithoutResult(st -> outbox.registrar(Destino.EXECUCAO, cmd));
        var carriers = new ArrayList<Map<String, String>>();
        assertThat(publisher((d, o, m, corpo, traco) -> { carriers.add(traco); return "ack"; }).publicarProximo()).isTrue();
        assertThat(carriers).containsExactly(Map.of());
    }
}
