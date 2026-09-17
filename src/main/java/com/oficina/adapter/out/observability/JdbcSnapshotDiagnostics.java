package com.oficina.adapter.out.observability;

import com.oficina.application.observability.SnapshotDiagnostics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.sql.Timestamp;

/** Reads only aggregate operational state in a short independent read transaction. */
public final class JdbcSnapshotDiagnostics {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcSnapshotDiagnostics(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(2);
    }

    public SnapshotDiagnostics current(long droppedExports) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '2s'");
            return jdbc.queryForObject("""
                    SELECT count(*) FILTER (WHERE estado='PENDING') AS pending,
                           count(*) FILTER (WHERE estado='BLOCKED') AS blocked,
                           coalesce(extract(epoch FROM (? - min(disponivel_em) FILTER (WHERE estado='PENDING'))), 0) AS oldest
                    FROM outbox_eventos
                    """, (rs, row) -> new SnapshotDiagnostics(rs.getLong("pending"), rs.getLong("blocked"),
                    Math.max(0, rs.getLong("oldest")), droppedExports), Timestamp.from(clock.instant()));
        });
    }
}
