package com.oficina.saga.adapter.out.messaging;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.oficina.adapter.out.outbox.BackoffPublicacao;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.repository.*;
import com.oficina.saga.adapter.out.persistence.JdbcSagaOutbox;
import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.application.port.EnviadorMensagens;
import com.oficina.saga.domain.Destino;
import com.oficina.support.PostgresIntegrationSupport;
import com.oficina.support.SagaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@Import({JdbcSagaOutbox.class, MapeadorMensagens.class})
class SagaOutboxPublisherTest extends PostgresIntegrationSupport {
    static final Instant T = Instant.parse("2026-09-15T12:00:00Z");
    @Autowired JdbcSagaOutbox outbox;
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
        var p = publisher((destino, osId, messageId, corpo) -> { enviados.add(destino + ":" + messageId); return "sqs-" + messageId; });
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
        var p = publisher((destino, osId, messageId, corpo) -> { throw new IllegalStateException("token=secret"); });
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
}
