package com.oficina.saga.adapter.out.messaging;

import com.oficina.adapter.out.outbox.BackoffPublicacao;
import com.oficina.config.CorrelationFilter;
import com.oficina.saga.application.port.EnviadorMensagens;
import com.oficina.saga.domain.Destino;
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

/** Same claim/ordering/backoff contract as the notification OutboxPublisher, over saga_outbox. */
public class SagaOutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(SagaOutboxPublisher.class);
    private static final String CLAIM = """
            SELECT o.message_id, o.os_id, o.destino, o.envelope::text AS corpo, o.tentativas,
                   o.envelope->>'correlationId' AS correlation_id, o.envelope->>'traceparent' AS traceparent
            FROM saga_outbox o
            WHERE o.estado='PENDING' AND o.disponivel_em <= ?
            AND NOT EXISTS (
                SELECT 1 FROM saga_outbox anterior
                WHERE anterior.os_id=o.os_id AND anterior.sequencia<o.sequencia
                AND anterior.estado IN ('PENDING','BLOCKED')
            )
            ORDER BY o.disponivel_em, o.sequencia
            FOR UPDATE OF o SKIP LOCKED LIMIT 1
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EnviadorMensagens enviador;
    private final Clock clock;
    private final BackoffPublicacao backoff;
    private Instant databaseRetryAt = Instant.MIN;

    public SagaOutboxPublisher(JdbcTemplate jdbc, TransactionTemplate transaction, EnviadorMensagens enviador,
                               Clock clock, BackoffPublicacao backoff) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transaction.getTransactionManager());
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(4);
        this.enviador = enviador;
        this.clock = clock;
        this.backoff = backoff;
    }

    /** True when one row was processed; false on no work or a rolled-back DB failure. */
    public synchronized boolean publicarProximo() {
        if (clock.instant().isBefore(databaseRetryAt)) return false;
        try {
            return Boolean.TRUE.equals(transaction.execute(tx -> {
                jdbc.execute("SET LOCAL statement_timeout = '1000ms'");
                jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                var rows = jdbc.query(CLAIM, (rs, n) -> new Pendente(rs.getObject("message_id", UUID.class),
                        rs.getObject("os_id", UUID.class), Destino.valueOf(rs.getString("destino")), rs.getString("corpo"),
                        rs.getInt("tentativas"), rs.getString("correlation_id"), rs.getString("traceparent")),
                        Timestamp.from(clock.instant()));
                if (rows.isEmpty()) return false;
                Pendente p = rows.get(0);
                String ack;
                try {
                    ack = enviarComRastreio(p);
                    if (ack == null || ack.isBlank()) throw new IllegalStateException("Missing queue acknowledgement");
                } catch (RuntimeException e) {
                    falhar(p, "QUEUE_SEND_FAILED");
                    return true;
                }
                jdbc.update("""
                        UPDATE saga_outbox SET estado='PUBLISHED', publicado_em=?, queue_message_id=?, ultimo_erro_codigo=NULL
                        WHERE message_id=?
                        """, Timestamp.from(clock.instant()), ack, p.messageId());
                return true;
            }));
        } catch (RuntimeException e) {
            databaseRetryAt = clock.instant().plusSeconds(5);
            MDC.put("event_name", "saga_outbox_publish_failed");
            MDC.put("error_code", "SAGA_OUTBOX_TRANSACTION_FAILED");
            try { LOG.warn(""); } finally { MDC.remove("event_name"); MDC.remove("error_code"); }
            return false;
        }
    }

    private String enviarComRastreio(Pendente p) {
        String priorCorrelation = MDC.get(CorrelationFilter.CORRELATION_ID);
        String priorTrace = MDC.get("traceparent");
        try {
            MDC.put(CorrelationFilter.CORRELATION_ID, p.correlationId());
            if (p.traceparent() != null) MDC.put("traceparent", p.traceparent());
            return enviador.enviar(p.destino(), p.osId(), p.messageId(), p.corpo());
        } finally {
            restaurar(CorrelationFilter.CORRELATION_ID, priorCorrelation);
            restaurar("traceparent", priorTrace);
        }
    }

    private static void restaurar(String chave, String valor) {
        if (valor == null) MDC.remove(chave); else MDC.put(chave, valor);
    }

    private void falhar(Pendente p, String codigo) {
        var tentativa = backoff.proxima(p.tentativas(), clock.instant());
        jdbc.update("""
                UPDATE saga_outbox SET tentativas=?, estado=?, disponivel_em=?, ultimo_erro_codigo=? WHERE message_id=?
                """, tentativa.numero(), tentativa.bloqueada() ? "BLOCKED" : "PENDING",
                Timestamp.from(tentativa.disponivelEm()), codigo, p.messageId());
    }

    private record Pendente(UUID messageId, UUID osId, Destino destino, String corpo, int tentativas,
                            String correlationId, String traceparent) {}
}
