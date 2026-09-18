package com.oficina.application.notificacao;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oficina.entity.StatusOrdemServico;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable B2/phase3-v1 message; no customer contact or persistence graph. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StatusOrdemServicoRegistrado(UUID eventId, String eventType, int schemaVersion,
        UUID ordemId, long numero, UUID clienteId, long versaoIdentidadeCliente, long sequencia,
        String statusAnterior, String statusNovo, Instant ocorridoEm, String correlationId, String traceparent) {
    public static final String EVENT_TYPE = "StatusOrdemServicoRegistrado";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 8192;

    public StatusOrdemServicoRegistrado {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(ordemId, "ordemId");
        Objects.requireNonNull(clienteId, "clienteId");
        Objects.requireNonNull(ocorridoEm, "ocorridoEm");
        if (!EVENT_TYPE.equals(eventType) || schemaVersion != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported notification contract");
        if (numero <= 0 || versaoIdentidadeCliente <= 0 || sequencia <= 0)
            throw new IllegalArgumentException("Positive notification references required");
        if (statusAnterior != null) StatusOrdemServico.valueOf(statusAnterior);
        StatusOrdemServico.valueOf(Objects.requireNonNull(statusNovo, "statusNovo"));
        if (correlationId == null || !correlationId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Invalid notification correlation reference");
        if (traceparent != null && (!traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                || traceparent.substring(3, 35).equals("0".repeat(32))
                || traceparent.substring(36, 52).equals("0".repeat(16))))
            throw new IllegalArgumentException("Invalid notification trace reference");
    }
}
