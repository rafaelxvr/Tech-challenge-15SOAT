package com.oficina.adapter.out.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import com.oficina.entity.*;
import com.oficina.repository.*;
import com.oficina.support.Fixtures;
import com.oficina.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OutboxPublisherTest extends PostgresIntegrationSupport {
    @Autowired ClienteRepository clientes;
    @Autowired OrdemServicoRepository ordens;
    @Autowired VeiculoRepository veiculos;
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    final List<StatusOrdemServicoRegistrado> sent = new ArrayList<>();
    MutableClock clock;
    OutboxPublisher publisher;
    Cliente customer;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM outbox_recuperacoes");
        jdbc.update("DELETE FROM outbox_eventos");
        clock = new MutableClock();
        customer = clientes.findByDocumento("39053344705").orElseGet(() -> clientes.save(Fixtures.cliente(null)));
        publisher = new OutboxPublisher(jdbc, transaction, event -> { sent.add(event); return "message-id"; }, clock, bound -> 0);
    }

    @Test void lockedPredecessorNeverAllowsOvertakingWhileOtherOrderPublishes() throws Exception {
        var a = order(); var b = order();
        var first = event(a, 1); event(a, 2); var independent = event(b, 1);
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT event_id FROM outbox_eventos WHERE event_id=? FOR UPDATE")) {
                statement.setObject(1, first.eventId()); statement.executeQuery().close();
                assertThat(publisher.publicarProximo()).isTrue();
                assertThat(sent).containsExactly(independent);
                assertThat(publisher.publicarProximo()).isFalse();
            } finally { connection.rollback(); }
        }
        assertThat(publisher.publicarProximo()).isTrue();
        assertThat(sent).containsExactly(independent, first);
    }

    @Test void blockedAndDelayedPredecessorsPreventOvertaking() throws Exception {
        var a = order(); var first = event(a, 1); event(a, 2);
        jdbc.update("UPDATE outbox_eventos SET disponivel_em=? WHERE event_id=?", java.sql.Timestamp.from(clock.instant().plusSeconds(60)), first.eventId());
        assertThat(publisher.publicarProximo()).isFalse();
        jdbc.update("UPDATE outbox_eventos SET estado='BLOCKED' WHERE event_id=?", first.eventId());
        assertThat(publisher.publicarProximo()).isFalse();
        assertThat(sent).isEmpty();
    }

    @Test void publishesExactlyOneAndPersistsAck() throws Exception {
        var a = order(); var first = event(a, 1); event(a, 2);
        assertThat(publisher.publicarProximo()).isTrue();
        assertThat(sent).containsExactly(first);
        assertThat(jdbc.queryForMap("SELECT estado, tentativas, queue_message_id FROM outbox_eventos WHERE event_id=?", first.eventId()))
                .containsEntry("estado", "PUBLISHED").containsEntry("tentativas", 0).containsEntry("queue_message_id", "message-id");
        assertThat(jdbc.queryForObject("SELECT publicado_em FROM outbox_eventos WHERE event_id=?", java.sql.Timestamp.class, first.eventId()).toInstant()).isEqualTo(clock.instant());
    }

    @Test void twelveFailuresBlockWithBoundedBackoffAndSafeError() throws Exception {
        var first = event(order(), 1);
        publisher = new OutboxPublisher(jdbc, transaction, event -> { throw new IllegalStateException("secret@example.invalid token=private"); }, clock, bound -> bound - 1);
        for (int attempt = 1; attempt <= 12; attempt++) {
            assertThat(publisher.publicarProximo()).isTrue();
            var state = jdbc.queryForMap("SELECT * FROM outbox_eventos WHERE event_id=?", first.eventId());
            assertThat(state).containsEntry("tentativas", attempt).containsEntry("estado", attempt == 12 ? "BLOCKED" : "PENDING")
                    .containsEntry("ultimo_erro_codigo", "QUEUE_SEND_FAILED");
            var due = ((java.sql.Timestamp) state.get("disponivel_em")).toInstant();
            assertThat(Duration.between(clock.instant(), due).toMillis()).isBetween(5000L, 300000L);
            assertThat(publisher.publicarProximo()).isFalse();
            clock.now = due;
        }
        assertThat(publisher.publicarProximo()).isFalse();
    }

    @Test void ackDatabaseFailureRollsBackAndResendsStableIdAfterLocalCooldown() throws Exception {
        var first = event(order(), 1);
        jdbc.execute("CREATE FUNCTION fail_a6_ack() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'CONTROLLED_A6_FAILURE'; END $$");
        jdbc.execute("CREATE TRIGGER fail_a6 BEFORE UPDATE ON outbox_eventos FOR EACH ROW EXECUTE FUNCTION fail_a6_ack()");
        try {
            assertThat(publisher.publicarProximo()).isFalse();
            assertThat(publisher.publicarProximo()).isFalse();
            assertThat(sent).containsExactly(first);
            assertThat(jdbc.queryForMap("SELECT estado,tentativas FROM outbox_eventos WHERE event_id=?", first.eventId()))
                    .containsEntry("estado", "PENDING").containsEntry("tentativas", 0);
            assertThat(ordens.existsById(first.ordemId())).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER fail_a6 ON outbox_eventos"); jdbc.execute("DROP FUNCTION fail_a6_ack()");
        }
        clock.now = clock.now.plusSeconds(5);
        assertThat(publisher.publicarProximo()).isTrue();
        assertThat(sent).containsExactly(first, first);
    }

    @Test void failureBeforeClaimDoesNotSpinAndDoesNotSend() throws Exception {
        var first = event(order(), 1);
        var broken = org.mockito.Mockito.spy(jdbc);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("private"))
                .when(broken).execute("SET LOCAL statement_timeout = '1000ms'");
        publisher = new OutboxPublisher(broken, transaction, event -> { sent.add(event); return "ack"; }, clock);
        assertThat(publisher.publicarProximo()).isFalse();
        assertThat(publisher.publicarProximo()).isFalse();
        org.mockito.Mockito.verify(broken).execute("SET LOCAL statement_timeout = '1000ms'");
        assertThat(sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT tentativas FROM outbox_eventos WHERE event_id=?", Integer.class, first.eventId())).isZero();
        clock.now = clock.now.plusSeconds(5);
        assertThat(publisher.publicarProximo()).isFalse();
        org.mockito.Mockito.verify(broken, org.mockito.Mockito.times(2)).execute("SET LOCAL statement_timeout = '1000ms'");
    }

    @Test void retentionUsesPublishedAgeAndPreservesAllAuditAndUnpublishedStates() throws Exception {
        var old = event(order(), 1); var fresh = event(order(), 1); var pending = event(order(), 1);
        var blocked = event(order(), 1); var audited = event(order(), 1); var skipped = event(order(), 1);
        jdbc.update("UPDATE outbox_eventos SET criado_em=CURRENT_TIMESTAMP - INTERVAL '30 days'");
        for (var event : List.of(old, fresh, audited))
            jdbc.update("UPDATE outbox_eventos SET estado='PUBLISHED',publicado_em=CURRENT_TIMESTAMP - INTERVAL '8 days' WHERE event_id=?", event.eventId());
        jdbc.update("UPDATE outbox_eventos SET publicado_em=CURRENT_TIMESTAMP - INTERVAL '7 days' WHERE event_id=?", fresh.eventId());
        // A fixed cutoff in the same transaction makes the exact seven-day boundary meaningful.
        jdbc.update("UPDATE outbox_eventos SET estado='BLOCKED' WHERE event_id=?", blocked.eventId());
        jdbc.update("UPDATE outbox_eventos SET estado='SKIPPED' WHERE event_id=?", skipped.eventId());
        jdbc.update("INSERT INTO outbox_recuperacoes(id,event_id,acao,operador_ref,motivo) VALUES (?,?,'RETRY','operator_1','INSPECTED_RETRY')", UUID.randomUUID(), audited.eventId());
        String script = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/database/purge-published-outbox.sql"));
        assertThat(script.indexOf("\\copy outbox_purge_candidates")).isLessThan(script.indexOf("-- BEGIN DELETE"));
        transaction.executeWithoutResult(tx -> {
            jdbc.update("UPDATE outbox_eventos SET publicado_em=CURRENT_TIMESTAMP - INTERVAL '7 days' WHERE event_id=?", fresh.eventId());
            jdbc.execute(script.split("-- BEGIN CANDIDATES")[1].split("-- END CANDIDATES")[0]);
            assertThat(jdbc.queryForList("SELECT event_id FROM outbox_purge_candidates", UUID.class)).containsExactly(old.eventId());
            jdbc.execute(script.split("-- BEGIN DELETE")[1].split("-- END DELETE")[0]);
        });
        assertThat(jdbc.queryForList("SELECT event_id FROM outbox_eventos", UUID.class))
                .containsExactlyInAnyOrder(fresh.eventId(), pending.eventId(), blocked.eventId(), audited.eventId(), skipped.eventId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_recuperacoes", Integer.class)).isEqualTo(1);
    }

    @Test void publisherSharesTheFiveConnectionApplicationPool() throws Exception {
        assertThat(jdbc.getDataSource().unwrap(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize()).isEqualTo(5);
        event(order(), 1);
        assertThat(publisher.publicarProximo()).isTrue();
    }

    @Test void psqlDefaultsToPreviewAndRequiresSuccessfulExportBeforeDeletion() throws Exception {
        var old = event(order(), 1); var pending = event(order(), 1);
        jdbc.update("UPDATE outbox_eventos SET estado='PUBLISHED',publicado_em=CURRENT_TIMESTAMP - INTERVAL '8 days' WHERE event_id=?", old.eventId());
        String script = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/database/purge-published-outbox.sql"));
        POSTGRES.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath("scripts/database/purge-published-outbox.sql"), "/tmp/purge-a6.sql");
        var preview = POSTGRES.execInContainer("psql", "-X", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-f", "/tmp/purge-a6.sql");
        assertThat(preview.getExitCode()).withFailMessage(preview.getStderr()).isZero();
        assertThat(preview.getStdout()).contains(old.eventId().toString(), "ROLLBACK");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos", Integer.class)).isEqualTo(2);
        POSTGRES.copyFileToContainer(org.testcontainers.images.builder.Transferable.of(
                script.replace("TO 'outbox-published-export.csv'", "TO '/does-not-exist/a6.csv'")), "/tmp/purge-a6-fail.sql");
        var failure = POSTGRES.execInContainer("psql", "-X", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-v", "delete_published=true", "-f", "/tmp/purge-a6-fail.sql");
        assertThat(failure.getExitCode()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos", Integer.class)).isEqualTo(2);
        var maintenance = POSTGRES.execInContainer("psql", "-X", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-v", "delete_published=true", "-f", "/tmp/purge-a6.sql");
        assertThat(maintenance.getExitCode()).withFailMessage(maintenance.getStderr()).isZero();
        assertThat(maintenance.getStdout()).contains("COPY 1", "DELETE 1", "COMMIT");
        assertThat(POSTGRES.execInContainer("cat", "outbox-published-export.csv").getStdout()).contains(old.eventId().toString()).doesNotContain(pending.eventId().toString());
        assertThat(jdbc.queryForList("SELECT event_id FROM outbox_eventos", UUID.class)).containsExactly(pending.eventId());
    }

    OrdemServico order() {
        var order = Fixtures.ordem(customer, StatusOrdemServico.EM_DIAGNOSTICO);
        order.setVeiculo(veiculos.save(Veiculo.builder().cliente(customer).placa(UUID.randomUUID().toString().substring(0, 8))
                .marca("Fiat").modelo("Uno").ano(2020).ativo(true).build()));
        return ordens.saveAndFlush(order);
    }

    StatusOrdemServicoRegistrado event(OrdemServico order, long sequence) throws Exception {
        var event = new StatusOrdemServicoRegistrado(UUID.randomUUID(), StatusOrdemServicoRegistrado.EVENT_TYPE, 1,
                order.getId(), order.getNumero(), customer.getId(), 1, sequence, null, "RECEBIDA", clock.instant(), UUID.randomUUID().toString(), null);
        jdbc.update("INSERT INTO outbox_eventos(event_id,os_id,sequencia,event_type,schema_version,payload,disponivel_em) VALUES (?,?,?,?,1,CAST(? AS jsonb),?)",
                event.eventId(), order.getId(), sequence, event.eventType(), mapper.writeValueAsString(event), java.sql.Timestamp.from(clock.instant()));
        return event;
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
