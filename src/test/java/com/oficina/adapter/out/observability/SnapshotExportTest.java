package com.oficina.adapter.out.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.application.observability.SnapshotDiagnostics;
import com.oficina.application.observability.SnapshotScheduler;
import com.oficina.application.relatorio.DuracaoStatus;
import com.oficina.application.relatorio.RelatorioPeriodo;
import com.oficina.application.relatorio.RelatoriosPort;
import com.oficina.application.relatorio.StatusAtual;
import com.oficina.entity.StatusOrdemServico;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotExportTest {
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void captures_seven_daily_one_rolling_current_status_outbox_and_heartbeat_before_export() {
        AtomicBoolean queriesClosed = new AtomicBoolean(false);
        List<Map<String, Object>> exported = new ArrayList<>();
        RelatoriosPort reports = new RelatoriosPort() {
            @Override public RelatorioPeriodo consultar(LocalDate start, LocalDate end, ZoneId zone) {
                return report();
            }
            @Override public List<StatusAtual> statusAtual(Instant now) {
                queriesClosed.set(true);
                return List.of(new StatusAtual(StatusOrdemServico.EM_DIAGNOSTICO, 3,
                        new BigDecimal("7200"), 2, 1));
            }
        };
        SnapshotScheduler scheduler = new SnapshotScheduler(reports, events -> {
            assertThat(queriesClosed).isTrue();
            exported.addAll(events);
        }, CLOCK, "staging", ZoneId.of("America/Sao_Paulo"),
                () -> new SnapshotDiagnostics(7, 2, 301, 0), () -> 0);

        scheduler.exportarAgora();

        assertThat(exported).filteredOn(e -> e.get("eventType").equals("WorkshopReportSnapshot")).hasSize(8);
        Map<String, Object> daily = exported.stream().filter(e -> "day".equals(e.get("window_kind"))).findFirst().orElseThrow();
        assertThat(daily).containsEntry("timestamp", NOW.toEpochMilli())
                .containsEntry("diagnosis_total_seconds", new BigDecimal("3600"))
                .containsEntry("diagnosis_samples", 2L)
                .containsEntry("execution_total_seconds", BigDecimal.ZERO)
                .containsEntry("execution_samples", 0L)
                .containsEntry("created_count", 4L)
                .containsEntry("eligible_count", 2L)
                .containsEntry("excluded_count", 1L);
        assertThat(exported).anySatisfy(e -> assertThat(e).containsEntry("eventType", "WorkshopStatusSnapshot")
                .containsEntry("known_age_count", 2L).containsEntry("unknown_age_count", 1L));
        assertThat(exported).anySatisfy(e -> assertThat(e).containsEntry("eventType", "WorkshopOutboxHealth")
                .containsEntry("pending_count", 7L).containsEntry("blocked_count", 2L).containsEntry("oldest_pending_seconds", 301L));
        assertThat(exported).anySatisfy(e -> assertThat(e).containsEntry("eventType", "WorkshopTelemetryHeartbeat")
                .containsEntry("drop_count", 0L));
    }

    @Test void exporter_sends_a_bounded_capture_time_batch_without_retry_and_tracks_drop() {
        List<byte[]> payloads = new ArrayList<>();
        NewRelicSnapshotExporter exporter = new NewRelicSnapshotExporter(
                URI.create("https://collector.example.invalid/events"), "123", "insert-key",
                (endpoint, account, key, body, timeout) -> {
                    assertThat(timeout).hasSeconds(2); assertThat(account).isEqualTo("123"); assertThat(key).isEqualTo("insert-key");
                    payloads.add(body);
                }, new ObjectMapper());
        exporter.exportar(List.of(Map.of("eventType", "WorkshopReportSnapshot", "environment", "staging", "timestamp", NOW.toEpochMilli(), "diagnosis_samples", 0L)));
        assertThat(new String(payloads.get(0))).contains("WorkshopReportSnapshot", "\"timestamp\":177".substring(0, 12));
        assertThat(exporter.droppedBatches()).isZero();
        assertThatThrownBy(() -> exporter.exportar(java.util.stream.IntStream.range(0, 33).mapToObj(i ->
                Map.<String, Object>of("eventType", "WorkshopReportSnapshot", "environment", "staging", "timestamp", NOW.toEpochMilli())).toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalid_endpoint_or_event_is_rejected_before_transport() {
        assertThatThrownBy(() -> new NewRelicSnapshotExporter(URI.create("http://collector.invalid"), "1", "key",
                (a, b, c, d, e) -> { }, new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
        NewRelicSnapshotExporter exporter = new NewRelicSnapshotExporter(URI.create("https://collector.invalid"), "1", "key",
                (a, b, c, d, e) -> { }, new ObjectMapper());
        assertThatThrownBy(() -> exporter.exportar(List.of(Map.of("eventType", "bad")))).isInstanceOf(IllegalArgumentException.class);
    }

    private static RelatorioPeriodo report() {
        Map<StatusOrdemServico, DuracaoStatus> durations = new EnumMap<>(StatusOrdemServico.class);
        durations.put(StatusOrdemServico.EM_DIAGNOSTICO, new DuracaoStatus(new BigDecimal("3600"), 2));
        return new RelatorioPeriodo(4, 2, 1, durations);
    }
}
