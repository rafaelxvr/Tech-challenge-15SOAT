package com.oficina.saga.adapter.out.persistence;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.application.rastreio.PropagadorTraco;
import com.oficina.saga.domain.Destino;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Calls the outbox write directly: tracing is best effort and the stored envelope never carries an unsafe carrier. */
class JdbcSagaOutboxCapturaTest {
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PropagadorTraco propagador = mock(PropagadorTraco.class);
    final JdbcSagaOutbox outbox = new JdbcSagaOutbox(jdbc, new MapeadorMensagens(), propagador);

    Envelope envelope() {
        return new Envelope(UUID.randomUUID(), Envelope.Kind.COMMAND, "IniciarDiagnostico", 1, UUID.randomUUID(), UUID.randomUUID(), 1,
                null, Instant.parse("2026-09-15T12:00:00Z"), UUID.randomUUID().toString(), null, JsonNodeFactory.instance.objectNode());
    }

    String gravado() {
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), any(Object.class), any(Object.class), any(Object.class), any(Object.class), captor.capture());
        return (String) captor.getValue();
    }

    @Test void propagadorQueLancaNaoImpedeAGravacao() {
        when(propagador.capturar()).thenThrow(new IllegalStateException("agent"));
        outbox.registrar(Destino.EXECUCAO, envelope());
        assertThat(gravado()).doesNotContain("_carrierRastreio");
    }

    @Test void erroDeLinkageDoAgenteNaoImpedeAGravacao() {
        when(propagador.capturar()).thenThrow(new NoClassDefFoundError("com/newrelic/api/agent/NewRelic"));
        outbox.registrar(Destino.EXECUCAO, envelope());
        assertThat(gravado()).doesNotContain("_carrierRastreio");
    }

    @Test void carrierNuloGravaEnvelopeIntacto() {
        when(propagador.capturar()).thenReturn(null);
        outbox.registrar(Destino.EXECUCAO, envelope());
        assertThat(gravado()).doesNotContain("_carrierRastreio");
    }

    @Test void carrierValidoEGravadoDentroDoEnvelope() {
        when(propagador.capturar()).thenReturn(Map.of("traceparent", TRACEPARENT));
        outbox.registrar(Destino.EXECUCAO, envelope());
        assertThat(gravado()).contains("\"_carrierRastreio\"").contains(TRACEPARENT);
    }

    @Test void carrierInvalidoOuForaDaAllowlistNaoVaza() {
        Map<String, String> sujo = new HashMap<>();
        sujo.put("traceparent", "00-xx");
        sujo.put("authorization", "Bearer segredo");
        sujo.put("tracestate", "a=b\r\nx-injetado: 1");
        when(propagador.capturar()).thenReturn(sujo);
        outbox.registrar(Destino.EXECUCAO, envelope());
        assertThat(gravado()).doesNotContain("segredo").doesNotContain("authorization").doesNotContain("injetado")
                .doesNotContain("00-xx");
    }
}
