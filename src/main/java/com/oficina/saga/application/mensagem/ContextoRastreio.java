package com.oficina.saga.application.mensagem;

import com.oficina.config.CorrelationFilter;
import org.slf4j.MDC;

import java.util.UUID;

public record ContextoRastreio(String correlationId, String traceparent) {
    /** Current request/message trace; a new correlation id when none is present (scheduler threads). */
    public static ContextoRastreio atual() {
        String correlation = MDC.get(CorrelationFilter.CORRELATION_ID);
        return new ContextoRastreio(correlation != null ? correlation : UUID.randomUUID().toString(), MDC.get("traceparent"));
    }
}
