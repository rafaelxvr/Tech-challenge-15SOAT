package com.oficina.saga.application.mensagem;

import com.oficina.config.CorrelationFilter;

public record ContextoRastreio(String correlationId, String traceparent) {
    /** Current validated trace: a new correlation id when none (or a malformed one) is present, no invalid traceparent. */
    public static ContextoRastreio atual() {
        return new ContextoRastreio(CorrelationFilter.correlationId(), CorrelationFilter.traceparent());
    }
}
