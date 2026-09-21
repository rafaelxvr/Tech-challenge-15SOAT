package com.oficina.application.observability;

import com.oficina.adapter.out.observability.JdbcSnapshotDiagnostics;
import com.oficina.adapter.out.relatorio.JdbcRelatoriosAdapter;
import com.oficina.application.relatorio.RelatoriosPort;
import com.oficina.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reproduces the production defect with the real Flyway schema and the real adapters wired the
 * same way {@link com.oficina.config.ObservabilitySnapshotsConfiguration} wires them: real
 * {@link JdbcRelatoriosAdapter} for {@code reports}, real {@link JdbcSnapshotDiagnostics} for
 * {@code diagnostics}, and the production-default zone (America/Sao_Paulo, since
 * {@code REPORTING_ZONE} is unset on the deployment). None of the existing scheduler tests do
 * this: they all fake {@link RelatoriosPort} and never touch the database, so this is the first
 * test that can show whether {@code SnapshotScheduler.capturar()} actually throws.
 */
@Import(JdbcRelatoriosAdapter.class)
class SnapshotSchedulerCaptureIntegrationTest extends PostgresIntegrationSupport {

    @Autowired RelatoriosPort reports;
    @Autowired PlatformTransactionManager transactionManager;

    UUID customer;
    UUID vehicle;
    final ZoneId zone = ZoneId.of("America/Sao_Paulo");

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE outbox_recuperacoes,outbox_eventos,os_historico,os_pecas,os_servicos,ordens_servico RESTART IDENTITY CASCADE");
        customer = UUID.randomUUID();
        vehicle = UUID.randomUUID();
        jdbc.update("INSERT INTO clientes(id,nome,tipo_documento,documento,email,telefone) VALUES (?,'Reports','CPF',?,'reports@example.invalid','11999999999')", customer, customer.toString().substring(0, 18));
        jdbc.update("INSERT INTO veiculos(id,placa,marca,modelo,ano,cliente_id) VALUES (?,?,'Teste','Teste',2025,?)", vehicle, vehicle.toString().substring(0, 8), customer);
    }

    private SnapshotScheduler scheduler(Instant now) {
        JdbcSnapshotDiagnostics diagnostics = new JdbcSnapshotDiagnostics(jdbc, transactionManager, Clock.fixed(now, ZoneOffset.UTC));
        return new SnapshotScheduler(reports, events -> { }, Clock.fixed(now, ZoneOffset.UTC),
                "staging", zone, () -> diagnostics.current(0L), () -> 0L);
    }

    /**
     * Regression test for the production defect: every tick failed with
     * {@code SNAPSHOT_CAPTURE_FAILED} because {@code statusEvent()} put a null
     * {@code max_age_seconds} (StatusAtual's documented "no known age" case) into a map it then
     * passed to {@code Map.copyOf}, which rejects null values. An empty schema alone reproduces it:
     * every one of the six statuses has zero current orders, so every {@code StatusAtual} carries a
     * null maximum age.
     */
    @Test void capturarSucceedsAgainstEmptySchemaAndOmitsUnknownMaxAge() {
        SnapshotScheduler scheduler = scheduler(Instant.parse("2026-09-21T18:06:12Z"));

        List<Map<String, Object>> events = assertDoesNotThrowCapturar(scheduler);

        List<Map<String, Object>> statusEvents = events.stream()
                .filter(e -> "WorkshopStatusSnapshot".equals(e.get("eventType"))).toList();
        assertThat(statusEvents).hasSize(6);
        assertThat(statusEvents).allSatisfy(e -> {
            assertThat(e).containsEntry("known_age_count", 0L).containsEntry("unknown_age_count", 0L);
            assertThat(e).doesNotContainKey("max_age_seconds");
        });
    }

    @Test void capturarSucceedsAgainstStagingLikeDataWithMixedKnownAndUnknownAges() {
        order("2026-09-15T04:00:00Z", true,
                new String[]{"RECEBIDA", "EM_DIAGNOSTICO", "AGUARDANDO_APROVACAO", "EM_EXECUCAO", "FINALIZADA", "ENTREGUE"},
                0, 5, 30, 35, 75, 85);
        order("2026-09-16T04:00:00Z", false, new String[]{"RECEBIDA", "EM_DIAGNOSTICO"}, 0, 5);
        order("2026-09-17T04:00:00Z", false, new String[]{"RECEBIDA", "EM_DIAGNOSTICO", "AGUARDANDO_APROVACAO", "EM_EXECUCAO"}, 0, 5, 30, 35);
        order("2026-09-18T04:00:00Z", true, new String[]{"RECEBIDA", "ENTREGUE"}, 0, 5);

        SnapshotScheduler scheduler = scheduler(Instant.parse("2026-09-21T18:06:12Z"));

        List<Map<String, Object>> events = assertDoesNotThrowCapturar(scheduler);

        List<Map<String, Object>> statusEvents = events.stream()
                .filter(e -> "WorkshopStatusSnapshot".equals(e.get("eventType"))).toList();
        assertThat(statusEvents).hasSize(6);
        // EM_EXECUCAO has one order with a known current age (the one still stuck there).
        Map<String, Object> execucao = statusEvents.stream()
                .filter(e -> "EM_EXECUCAO".equals(e.get("status"))).findFirst().orElseThrow();
        assertThat(execucao).containsKey("max_age_seconds");
        // FINALIZADA currently has no orders at all, so its age is unknown and the key is omitted.
        Map<String, Object> finalizada = statusEvents.stream()
                .filter(e -> "FINALIZADA".equals(e.get("status"))).findFirst().orElseThrow();
        assertThat(finalizada).containsEntry("current_count", 0L).doesNotContainKey("max_age_seconds");
    }

    private static List<Map<String, Object>> assertDoesNotThrowCapturar(SnapshotScheduler scheduler) {
        List<Map<String, Object>>[] captured = new List[1];
        assertThatCode(() -> captured[0] = scheduler.capturar()).doesNotThrowAnyException();
        return captured[0];
    }

    UUID order(String start, boolean complete, String[] statuses, int... minutes) {
        UUID id = UUID.randomUUID();
        var created = Instant.parse(start).atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO ordens_servico(id,cliente_id,veiculo_id,status,criado_em_utc,historico_completo_desde_inicio,sequencia_historico) VALUES (?,?,?,?::status_os,?,?,?)",
                id, customer, vehicle, statuses[statuses.length - 1], created, complete, statuses.length);
        for (int i = 0; i < statuses.length; i++) {
            jdbc.update("INSERT INTO os_historico(os_id,status_anterior,status_novo,ator_tipo,ocorrido_em,sequencia) VALUES (?,?::status_os,?::status_os,'SYSTEM',?,?)",
                    id, i == 0 ? null : statuses[i - 1], statuses[i], created.plusMinutes(minutes[i]), i + 1);
        }
        return id;
    }
}
