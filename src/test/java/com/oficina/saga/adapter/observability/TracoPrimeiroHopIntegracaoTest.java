package com.oficina.saga.adapter.observability;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.newrelic.api.agent.Headers;
import com.newrelic.api.agent.Transaction;
import com.oficina.adapter.out.outbox.BackoffPublicacao;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.repository.*;
import com.oficina.saga.adapter.out.messaging.SagaOutboxPublisher;
import com.oficina.saga.adapter.out.persistence.JdbcSagaOutbox;
import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.application.rastreio.ContextoCarrierHttp;
import com.oficina.saga.application.rastreio.PropagadorTraco;
import com.oficina.saga.domain.Destino;
import com.oficina.support.PostgresIntegrationSupport;
import com.oficina.support.SagaFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Regression for the first HTTP hop: the agent hands out headers only at the start of the web transaction; by the time the
 * outbox row is written the late capture is empty, and the first command used to leave without a trace.
 */
@Import({JdbcSagaOutbox.class, MapeadorMensagens.class, TracoPrimeiroHopIntegracaoTest.Config.class})
class TracoPrimeiroHopIntegracaoTest extends PostgresIntegrationSupport {
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    static final AtomicInteger CHAMADAS = new AtomicInteger();

    /** First capture (filter, web transaction fresh) yields headers; every later capture (inside the write) is empty. */
    @TestConfiguration
    static class Config {
        @Bean PropagadorTraco propagador() {
            Supplier<Transaction> agente = () -> {
                Transaction t = mock(Transaction.class);
                if (CHAMADAS.getAndIncrement() == 0) {
                    doAnswer(i -> {
                        Headers h = i.getArgument(0);
                        h.setHeader("traceparent", TRACEPARENT);
                        h.setHeader("tracestate", "123@nr=0-0-1-2-3");
                        return null;
                    }).when(t).insertDistributedTraceHeaders(any());
                }
                return t;
            };
            return new NewRelicPropagadorTraco(agente, (c, v) -> {});
        }
    }

    @RestController
    static class ControladorFalso {
        final JdbcSagaOutbox outbox;
        final org.springframework.transaction.support.TransactionTemplate tx;
        final Envelope envelope;
        final List<Map<String, String>> contextoVisto = new ArrayList<>();

        ControladorFalso(JdbcSagaOutbox outbox, org.springframework.transaction.support.TransactionTemplate tx, Envelope envelope) {
            this.outbox = outbox; this.tx = tx; this.envelope = envelope;
        }

        @GetMapping("/os")
        String criar() {
            contextoVisto.add(ContextoCarrierHttp.atual());
            tx.executeWithoutResult(st -> outbox.registrar(Destino.EXECUCAO, envelope));
            return "ok";
        }
    }

    @Autowired JdbcSagaOutbox outbox;
    @Autowired PropagadorTraco propagador;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired OrdemServicoRepository ordens;
    @Autowired PecaRepository pecas;
    @Autowired ServicoRepository servicos;
    final Clock clock = Clock.fixed(Instant.now().plusSeconds(60), ZoneOffset.UTC);

    @BeforeEach void limpar() {
        CHAMADAS.set(0);
        ContextoCarrierHttp.limpar();
        jdbc.update("DELETE FROM saga_outbox");
    }

    @AfterEach void limparContexto() { ContextoCarrierHttp.limpar(); }

    Envelope comando(UUID osId) {
        return new Envelope(UUID.randomUUID(), Envelope.Kind.COMMAND, "IniciarDiagnostico", 1, UUID.randomUUID(), osId, 1, null,
                Instant.parse("2026-09-15T12:00:00Z"), UUID.randomUUID().toString(), null, JsonNodeFactory.instance.objectNode());
    }

    MockMvc mvc(ControladorFalso controlador) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PropagadorTraco> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(propagador);
        return MockMvcBuilders.standaloneSetup(controlador).addFilters(new PreservaTracoHttpFilter(provider)).build();
    }

    @Test void primeiroComandoSaiComCarrierValidoEContextoELimpoEntreRequests() throws Exception {
        var cliente = SagaFixtures.cliente(clientes);
        UUID os = SagaFixtures.ordem(transaction, ordens, veiculos, pecas, servicos, cliente,
                SagaFixtures.veiculo(jdbc, cliente.getId()), StatusOrdemServico.RECEBIDA, Map.of(), Map.of());
        var controlador = new ControladorFalso(outbox, transaction, comando(os));
        var mvc = mvc(controlador);

        mvc.perform(get("/os")).andReturn();

        assertThat(controlador.contextoVisto.get(0)).containsEntry("traceparent", TRACEPARENT);
        assertThat(ContextoCarrierHttp.atual()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT envelope->'_carrierRastreio'->>'traceparent' FROM saga_outbox", String.class))
                .isEqualTo(TRACEPARENT);

        var corpos = new ArrayList<String>();
        var carriers = new ArrayList<Map<String, String>>();
        var publisher = new SagaOutboxPublisher(jdbc, transaction,
                (destino, osId, messageId, corpo, traco) -> { corpos.add(corpo); carriers.add(traco); return "ack"; },
                clock, new BackoffPublicacao(b -> 0));
        assertThat(publisher.publicarProximo()).isTrue();
        assertThat(carriers.get(0)).containsEntry("traceparent", TRACEPARENT).containsEntry("tracestate", "123@nr=0-0-1-2-3");
        assertThat(corpos.get(0)).doesNotContain("_carrierRastreio");

        // Second request: the agent has nothing to give and the previous carrier must not leak into it.
        jdbc.update("DELETE FROM saga_outbox");
        mvc.perform(get("/os")).andReturn();
        assertThat(controlador.contextoVisto.get(1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(envelope, '_carrierRastreio') FROM saga_outbox", Boolean.class)).isFalse();
    }
}
