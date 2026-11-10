package com.oficina.saga.application.rastreio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The only trace headers allowed on a queue message: a closed name list and bounded printable-ASCII values. */
public final class CarrierRastreio {
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";
    public static final String NEWRELIC = "newrelic";
    public static final List<String> NOMES = List.of(TRACEPARENT, TRACESTATE, NEWRELIC);
    private static final int MAX_VALOR = 1024;
    private static final Pattern VALOR = Pattern.compile("[\\x20-\\x7E]{1," + MAX_VALOR + "}");
    private static final Pattern TRACEPARENT_VALIDO = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    /**
     * Key under which the carrier rides inside the stored outbox envelope (no schema change: the release contract
     * pins V1-V11). The publisher strips it, so the wire body stays exactly the v1 envelope.
     */
    public static final String CHAVE_ARMAZENADA = "_carrierRastreio";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CarrierRastreio() {}

    /** The envelope JSON plus the carrier, or the untouched envelope when there is no carrier or no room for it. */
    public static String embutir(String corpo, Map<String, String> carrier, int maxBytes) {
        if (carrier == null || carrier.isEmpty()) return corpo;
        try {
            ObjectNode armazenado = (ObjectNode) MAPPER.readTree(corpo);
            armazenado.set(CHAVE_ARMAZENADA, MAPPER.valueToTree(sanitizar(carrier)));
            String json = MAPPER.writeValueAsString(armazenado);
            return json.getBytes(StandardCharsets.UTF_8).length <= maxBytes ? json : corpo;
        } catch (JsonProcessingException | ClassCastException e) {
            return corpo;
        }
    }

    /** Reads a stored carrier; anything absent, malformed or unsafe yields an empty carrier. */
    public static Map<String, String> ler(String json) {
        if (json == null) return Map.of();
        try {
            JsonNode no = MAPPER.readTree(json);
            Map<String, String> bruto = new LinkedHashMap<>();
            if (no != null && no.isObject()) no.fields().forEachRemaining(e -> { if (e.getValue().isTextual()) bruto.put(e.getKey(), e.getValue().asText()); });
            return sanitizar(bruto);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /** Keeps only known headers with a safe value; a malformed traceparent drops the whole carrier. */
    public static Map<String, String> sanitizar(Map<String, String> bruto) {
        Map<String, String> limpo = new LinkedHashMap<>();
        if (bruto == null) return limpo;
        for (String nome : NOMES) {
            String valor = bruto.get(nome);
            if (valor != null && VALOR.matcher(valor).matches()) limpo.put(nome, valor);
        }
        String traceparent = limpo.get(TRACEPARENT);
        if (traceparent != null && !TRACEPARENT_VALIDO.matcher(traceparent).matches()) limpo.clear();
        return limpo;
    }
}
