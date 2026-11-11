package com.oficina.saga.application.rastreio;

import java.util.Map;

/**
 * Carrier of the HTTP web transaction, kept for the duration of one request on the request thread. The outbox write
 * happens deep inside the producing transaction, where the agent can no longer hand out headers; this is the fallback.
 * Always cleared by whoever sets it (try/finally), so a pooled worker thread never leaks it into the next request.
 */
public final class ContextoCarrierHttp {
    private static final ThreadLocal<Map<String, String>> ATUAL = new ThreadLocal<>();

    private ContextoCarrierHttp() {}

    /** Stores the carrier (sanitized) for this thread; an empty carrier is stored as absent. */
    public static void preservar(Map<String, String> carrier) {
        Map<String, String> limpo = CarrierRastreio.sanitizar(carrier);
        if (limpo.isEmpty()) ATUAL.remove(); else ATUAL.set(Map.copyOf(limpo));
    }

    /** The carrier preserved on this thread, or an empty map. */
    public static Map<String, String> atual() {
        Map<String, String> carrier = ATUAL.get();
        return carrier == null ? Map.of() : carrier;
    }

    public static void limpar() { ATUAL.remove(); }
}
