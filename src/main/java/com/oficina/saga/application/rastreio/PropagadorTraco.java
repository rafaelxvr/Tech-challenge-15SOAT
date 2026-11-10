package com.oficina.saga.application.rastreio;

import java.util.Map;

/** Distributed-trace context crossing the SQS boundary; every operation is best effort and never fails the saga. */
public interface PropagadorTraco {
    /** Headers (traceparent, tracestate, newrelic) of the transaction active on this thread; empty when none. */
    Map<String, String> capturar();

    /** Joins the active transaction to the trace carried by a consumed message. */
    void aceitar(Map<String, String> headers);

    /** Adds a custom attribute to the active transaction. */
    void anotar(String chave, String valor);
}
