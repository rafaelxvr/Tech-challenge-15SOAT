package com.oficina.adapter.out.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oficina.application.notificacao.PublicadorFila;
import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.LongUnaryOperator;

/** Holds only the claimed outbox row lock through one send and acknowledgement. */
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final String CLAIM = """
            SELECT e.event_id, e.payload::text, e.tentativas FROM outbox_eventos e
            WHERE e.estado='PENDING' AND e.disponivel_em <= ?
            AND NOT EXISTS (
                SELECT 1 FROM outbox_eventos anterior
                WHERE anterior.os_id=e.os_id AND anterior.sequencia<e.sequencia
                AND anterior.estado IN ('PENDING','BLOCKED')
            )
            ORDER BY e.disponivel_em,e.criado_em
            FOR UPDATE OF e SKIP LOCKED LIMIT 1
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PublicadorFila queue;
    private final Clock clock;
    private final LongUnaryOperator jitter;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private Instant databaseRetryAt = Instant.MIN;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate transaction, PublicadorFila queue, Clock clock) {
        this(jdbc, transaction, queue, clock, bound -> java.util.concurrent.ThreadLocalRandom.current().nextLong(bound));
    }

    /** jitter returns an offset in [0,bound); clamped defensively to keep the bound. */
    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate transaction, PublicadorFila queue,
                           Clock clock, LongUnaryOperator jitter) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transaction.getTransactionManager());
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(4);
        this.queue = queue;
        this.clock = clock;
        this.jitter = jitter;
    }

    /** True when one row was processed; false on no work or a rolled-back DB failure. */
    public synchronized boolean publicarProximo() {
        if (clock.instant().isBefore(databaseRetryAt)) return false;
        try {
            return Boolean.TRUE.equals(transaction.execute(tx -> {
                // Bound lock/query waits as well as the SDK call; never touch order/stock locks.
                jdbc.execute("SET LOCAL statement_timeout = '1000ms'");
                jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                var rows = jdbc.query(CLAIM, (rs, row) -> new Pending(rs.getObject("event_id", UUID.class),
                        rs.getString("payload"), rs.getInt("tentativas")), Timestamp.from(clock.instant()));
                if (rows.isEmpty()) return false;
                var event = rows.get(0);
                String messageId;
                try {
                    StatusOrdemServicoRegistrado notification = mapper.readValue(event.payload(), StatusOrdemServicoRegistrado.class);
                    String priorCorrelation = MDC.get("correlation_id");
                    String priorTrace = MDC.get("traceparent");
                    try {
                        MDC.put("correlation_id", notification.correlationId());
                        if (notification.traceparent() != null) MDC.put("traceparent", notification.traceparent());
                        messageId = queue.enviar(notification);
                    } finally {
                        restoreMdc("correlation_id", priorCorrelation); restoreMdc("traceparent", priorTrace);
                    }
                    if (messageId == null || messageId.isBlank()) throw new IllegalStateException("Missing queue acknowledgement");
                } catch (JsonProcessingException exception) {
                    fail(event, "INVALID_EVENT_PAYLOAD");
                    return true;
                } catch (RuntimeException exception) {
                    fail(event, "QUEUE_SEND_FAILED");
                    return true;
                }
                // Outside the send catch: a DB error must roll back, never run more SQL in an aborted tx.
                jdbc.update("""
                        UPDATE outbox_eventos SET estado='PUBLISHED', publicado_em=?, queue_message_id=?, ultimo_erro_codigo=NULL
                        WHERE event_id=?
                        """, Timestamp.from(clock.instant()), messageId, event.id());
                return true;
            }));
        } catch (RuntimeException exception) {
            databaseRetryAt = clock.instant().plusSeconds(5);
            // Never log exception text, payload, contact, credentials or arbitrary provider error codes.
            LOG.warn("event_name=outbox_publish_failed error_code=OUTBOX_TRANSACTION_FAILED");
            return false;
        }
    }

    private static void restoreMdc(String key, String value) { if (value == null) MDC.remove(key); else MDC.put(key, value); }

    private void fail(Pending event, String code) {
        int attempt = Math.min(12, event.attempts() + 1);
        long cap = Math.min(300_000L, 5_000L << (attempt - 1));
        long offset = Math.max(0, Math.min(cap - 5_000L, jitter.applyAsLong(cap - 5_000L + 1)));
        jdbc.update("""
                UPDATE outbox_eventos SET tentativas=?, estado=?, disponivel_em=?, ultimo_erro_codigo=?
                WHERE event_id=?
                """, attempt, attempt == 12 ? "BLOCKED" : "PENDING",
                Timestamp.from(clock.instant().plusMillis(5_000L + offset)), code, event.id());
    }

    private record Pending(UUID id, String payload, int attempts) {}
}
