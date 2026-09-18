package com.oficina.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import com.oficina.application.port.out.NotificacaoPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Component
public class OutboxNotificacaoAdapter implements NotificacaoPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxNotificacaoAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void notificarAtualizacaoStatus(StatusOrdemServicoRegistrado evento) {
        Objects.requireNonNull(evento, "evento");
        String payload;
        try { payload = mapper.writeValueAsString(evento); }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize notification contract");
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > StatusOrdemServicoRegistrado.MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Notification payload exceeds limit");
        // A duplicate or failed insert must roll back the business mutation.
        jdbc.update("""
                INSERT INTO outbox_eventos(event_id, os_id, sequencia, event_type, schema_version, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, evento.eventId(), evento.ordemId(), evento.sequencia(), evento.eventType(), evento.schemaVersion(), payload);
    }
}
