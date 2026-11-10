package com.oficina.saga.adapter.observability;

import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.Headers;
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
