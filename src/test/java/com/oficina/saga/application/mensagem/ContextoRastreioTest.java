package com.oficina.saga.application.mensagem;

import com.oficina.config.CorrelationFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class ContextoRastreioTest {
    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @AfterEach void limpar() { MDC.clear(); }

    @Test void rastreioValidoDoMdcEPreservado() {
        MDC.put(CorrelationFilter.CORRELATION_ID, "00000000-0000-0000-0000-000000000123");
        MDC.put("traceparent", TRACE);
        assertThat(ContextoRastreio.atual())
                .isEqualTo(new ContextoRastreio("00000000-0000-0000-0000-000000000123", TRACE));
    }

    @Test void correlacaoInvalidaViraNovoUuidETraceparentInvalidoViraNulo() {
        MDC.put(CorrelationFilter.CORRELATION_ID, "x\"} injetado");
        MDC.put("traceparent", "01-invalido");
        var rastreio = ContextoRastreio.atual();
        assertThat(rastreio.correlationId()).matches(UUID_PATTERN);
        assertThat(rastreio.traceparent()).isNull();
    }

    @Test void semRastreioNoMdcGeraCorrelacaoNova() {
        var rastreio = ContextoRastreio.atual();
        assertThat(rastreio.correlationId()).matches(UUID_PATTERN);
        assertThat(rastreio.traceparent()).isNull();
    }
}
