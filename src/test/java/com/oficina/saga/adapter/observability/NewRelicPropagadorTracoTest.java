package com.oficina.saga.adapter.observability;

import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.Headers;
import com.newrelic.api.agent.TraceMetadata;
import com.newrelic.api.agent.Transaction;
import com.newrelic.api.agent.TransportType;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.oficina.saga.application.rastreio.ContextoCarrierHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NewRelicPropagadorTracoTest {
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    final Transaction transacao = mock(Transaction.class);
    final List<String> atributos = new ArrayList<>();
    final NewRelicPropagadorTraco propagador = new NewRelicPropagadorTraco(() -> transacao,
            (chave, valor) -> atributos.add(chave + "=" + valor));

    @Test void capturaOsHeadersQueOAgenteInsere() {
        doAnswer(i -> {
            Headers h = i.getArgument(0);
            assertThat(h.getHeaderType()).isEqualTo(HeaderType.MESSAGE);
            h.setHeader("traceparent", TRACEPARENT);
            h.setHeader("tracestate", "123@nr=0-0-1-2-3");
            h.setHeader("newrelic", "eyJ2IjpbMCwxXX0=");
            return null;
        }).when(transacao).insertDistributedTraceHeaders(any());
        assertThat(propagador.capturar()).containsEntry("traceparent", TRACEPARENT)
                .containsEntry("tracestate", "123@nr=0-0-1-2-3").containsEntry("newrelic", "eyJ2IjpbMCwxXX0=");
    }

    @Test void semAgenteOCarrierVemVazio() {
        assertThat(propagador.capturar()).isEmpty();
    }

    @Test void falhaDoAgenteNaoQuebraACaptura() {
        doThrow(new IllegalStateException("agent")).when(transacao).insertDistributedTraceHeaders(any());
        assertThat(propagador.capturar()).isEmpty();
    }

    // ---- outcomes estruturados, sem valores sensiveis no log ----
    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach void anexarLog() {
        logger = (Logger) LoggerFactory.getLogger(NewRelicPropagadorTraco.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        ContextoCarrierHttp.limpar();
    }

    @AfterEach void soltarLog() {
        logger.detachAppender(logs);
        ContextoCarrierHttp.limpar();
    }

    String mensagens() { return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b); }

    @Test void outcomeOkNaoVazaValores() {
        doAnswer(i -> {
            Headers h = i.getArgument(0);
            h.setHeader("traceparent", TRACEPARENT);
            h.setHeader("tracestate", "123@nr=segredo");
            h.setHeader("newrelic", "eyJ2IjpbMCwxXX0=");
            return null;
        }).when(transacao).insertDistributedTraceHeaders(any());
        propagador.capturar();
        assertThat(mensagens()).contains("outcome=ok").doesNotContain(TRACEPARENT).doesNotContain("segredo")
                .doesNotContain("eyJ2").doesNotContain("4bf92f35");
    }

    @Test void outcomeSemTransacao() {
        var sem = new NewRelicPropagadorTraco(() -> null, (c, v) -> {});
        assertThat(sem.capturar()).isEmpty();
        assertThat(mensagens()).contains("outcome=sem_transacao");
    }

    @Test void outcomeSemHeaders() {
        assertThat(propagador.capturar()).isEmpty();
        assertThat(mensagens()).contains("outcome=sem_headers");
    }

    @Test void outcomeExcecaoNaoVazaMensagemDaExcecao() {
        doThrow(new IllegalStateException("traceparent=" + TRACEPARENT)).when(transacao).insertDistributedTraceHeaders(any());
        assertThat(propagador.capturar()).isEmpty();
        assertThat(mensagens()).contains("outcome=excecao").doesNotContain(TRACEPARENT);
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    }

    @Test void outcomeDescartadoNaSanitizacao() {
        doAnswer(i -> {
            ((Headers) i.getArgument(0)).setHeader("traceparent", "00-xx");
            return null;
        }).when(transacao).insertDistributedTraceHeaders(any());
        assertThat(propagador.capturar()).isEmpty();
        assertThat(mensagens()).contains("outcome=descartado_sanitizacao");
    }

    @Test void capturaTardiaVaziaUsaOCarrierDoContextoHttp() {
        ContextoCarrierHttp.preservar(Map.of("traceparent", TRACEPARENT, "tracestate", "a=b"));
        assertThat(propagador.capturar()).containsEntry("traceparent", TRACEPARENT).containsEntry("tracestate", "a=b");
    }

    @Test void capturaTardiaValidaTemPrioridadeSobreOContexto() {
        String outro = "00-11111111111111111111111111111111-2222222222222222-01";
        ContextoCarrierHttp.preservar(Map.of("traceparent", outro));
        doAnswer(i -> {
            ((Headers) i.getArgument(0)).setHeader("traceparent", TRACEPARENT);
            return null;
        }).when(transacao).insertDistributedTraceHeaders(any());
        assertThat(propagador.capturar()).containsEntry("traceparent", TRACEPARENT);
    }

    @Test void fallbackNuncaLancaMesmoComSupplierQuebrado() {
        var quebrado = new NewRelicPropagadorTraco(() -> { throw new IllegalStateException("x"); }, (c, v) -> {});
        ContextoCarrierHttp.preservar(Map.of("traceparent", TRACEPARENT));
        assertThat(quebrado.capturar()).containsEntry("traceparent", TRACEPARENT);
        ContextoCarrierHttp.limpar();
        assertThat(quebrado.capturar()).isEmpty();
    }

    // ---- agente frio: sem headers, o carrier nasce dos ids da transacao ativa (TraceMetadata) ----
    static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String SPAN_ID = "00f067aa0ba902b7";

    TraceMetadata metadata(String traceId, String spanId, boolean sampled) {
        TraceMetadata m = mock(TraceMetadata.class);
        when(m.getTraceId()).thenReturn(traceId);
        when(m.getSpanId()).thenReturn(spanId);
        when(m.isSampled()).thenReturn(sampled);
        return m;
    }

    NewRelicPropagadorTraco comMetadata(TraceMetadata m) {
        return new NewRelicPropagadorTraco(() -> transacao, (c, v) -> {}, () -> m);
    }

    @Test void semHeadersMontaTraceparentW3cDoTraceMetadata() {
        var p = comMetadata(metadata(TRACE_ID, SPAN_ID, true));
        assertThat(p.capturar()).containsOnlyKeys("traceparent")
                .containsEntry("traceparent", "00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(mensagens()).contains("outcome=ok_metadata").doesNotContain(TRACE_ID).doesNotContain(SPAN_ID);
    }

    @Test void transacaoNaoAmostradaViraFlagZero() {
        assertThat(comMetadata(metadata(TRACE_ID, SPAN_ID, false)).capturar())
                .containsEntry("traceparent", "00-" + TRACE_ID + "-" + SPAN_ID + "-00");
    }

    @Test void headersDoAgenteTemPrioridadeSobreOTraceMetadata() {
        doAnswer(i -> {
            ((Headers) i.getArgument(0)).setHeader("traceparent", TRACEPARENT);
            return null;
        }).when(transacao).insertDistributedTraceHeaders(any());
        assertThat(comMetadata(metadata("1".repeat(32), "2".repeat(16), true)).capturar())
                .containsEntry("traceparent", TRACEPARENT);
    }

    @Test void metadataInvalidaOuAusenteNaoInventaCarrier() {
        assertThat(comMetadata(null).capturar()).isEmpty();
        assertThat(comMetadata(metadata(null, SPAN_ID, true)).capturar()).isEmpty();
        assertThat(comMetadata(metadata(TRACE_ID, null, true)).capturar()).isEmpty();
        assertThat(comMetadata(metadata("", "", true)).capturar()).isEmpty();
        assertThat(comMetadata(metadata("0".repeat(32), SPAN_ID, true)).capturar()).isEmpty();
        assertThat(comMetadata(metadata(TRACE_ID, "0".repeat(16), true)).capturar()).isEmpty();
        assertThat(comMetadata(metadata(TRACE_ID.toUpperCase(), SPAN_ID, true)).capturar()).isEmpty();
        assertThat(comMetadata(metadata("abc", "def", true)).capturar()).isEmpty();
        assertThat(comMetadata(metadata(TRACE_ID + "-01\r\nx: y", SPAN_ID, true)).capturar()).isEmpty();
        assertThat(mensagens()).contains("outcome=sem_headers");
    }

    @Test void metadataQueLancaNaoQuebraACaptura() {
        var p = new NewRelicPropagadorTraco(() -> transacao, (c, v) -> {}, () -> { throw new IllegalStateException("x"); });
        assertThat(p.capturar()).isEmpty();
        assertThat(mensagens()).contains("outcome=excecao");
    }

    @Test void semTransacaoNaoUsaMetadata() {
        var p = new NewRelicPropagadorTraco(() -> null, (c, v) -> {}, () -> metadata(TRACE_ID, SPAN_ID, true));
        assertThat(p.capturar()).isEmpty();
    }

    @Test void logInfoAmostradoDepoisDasPrimeirasCapturas() {
        for (int i = 0; i < 150; i++) propagador.capturar();
        long info = logs.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO).count();
        assertThat(info).isEqualTo(21); // 20 primeiras + a de numero 100
    }

    @Test void aceitaOsHeadersComoFilaSemDistinguirCaixa() {
        propagador.aceitar(Map.of("traceparent", TRACEPARENT, "tracestate", "123@nr=0-0-1-2-3"));
        var captor = ArgumentCaptor.forClass(Headers.class);
        verify(transacao).acceptDistributedTraceHeaders(eq(TransportType.Queue), captor.capture());
        Headers h = captor.getValue();
        assertThat(h.getHeaderType()).isEqualTo(HeaderType.MESSAGE);
        assertThat(h.getHeader("TraceParent")).isEqualTo(TRACEPARENT);
        assertThat(h.containsHeader("tracestate")).isTrue();
        assertThat(h.getHeader("newrelic")).isNull();
    }

    @Test void naoAceitaCarrierVazioOuMalformado() {
        propagador.aceitar(Map.of());
        propagador.aceitar(Map.of("traceparent", "malformado"));
        verifyNoInteractions(transacao);
    }

    @Test void anotaAtributoCustomizado() {
        propagador.anotar("sagaId", "id");
        assertThat(atributos).containsExactly("sagaId=id");
    }
}
