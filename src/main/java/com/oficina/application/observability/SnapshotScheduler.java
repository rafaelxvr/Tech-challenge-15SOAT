package com.oficina.application.observability;

import com.oficina.application.relatorio.DuracaoStatus;
import com.oficina.application.relatorio.RelatorioPeriodo;
import com.oficina.application.relatorio.RelatoriosPort;
import com.oficina.application.relatorio.StatusAtual;
import com.oficina.entity.StatusOrdemServico;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds bounded, capture-time report snapshots. All reads complete before the Event API is called,
 * so an unavailable telemetry endpoint cannot hold a reporting transaction or business request.
 */
public final class SnapshotScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotScheduler.class);
    private static final List<StatusOrdemServico> DURATION_STATUSES = List.of(
            StatusOrdemServico.EM_DIAGNOSTICO, StatusOrdemServico.EM_EXECUCAO, StatusOrdemServico.FINALIZADA);

    private final RelatoriosPort reports;
    private final SnapshotExporter exporter;
    private final Clock clock;
    private final String environment;
    private final ZoneId zone;
    private final java.util.function.LongSupplier droppedExports;
    private final java.util.function.Supplier<SnapshotDiagnostics> diagnostics;

    public SnapshotScheduler(RelatoriosPort reports, SnapshotExporter exporter, Clock clock,
                             String environment, ZoneId zone,
                             java.util.function.Supplier<SnapshotDiagnostics> diagnostics,
                             java.util.function.LongSupplier droppedExports) {
        this.reports = Objects.requireNonNull(reports);
        this.exporter = Objects.requireNonNull(exporter);
        this.clock = Objects.requireNonNull(clock);
        this.environment = requireEnvironment(environment);
        this.zone = Objects.requireNonNull(zone);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.droppedExports = Objects.requireNonNull(droppedExports);
    }

    /**
     * Runs one safe capture and export. The two phases are contained separately so a database
     * failure while capturing and an HTTP failure while exporting to New Relic surface as distinct
     * error codes; both are still explicitly contained outside domain transactions. The outer
     * containment reports and swallows anything the two inner {@code catch (RuntimeException ...)}
     * blocks do not, such as an ordinary {@link Error}: {@code scheduleWithFixedDelay} permanently
     * cancels a repeating task whose run throws, so this tick must never propagate one of those.
     * A {@link VirtualMachineError} (e.g. {@code OutOfMemoryError}, {@code StackOverflowError}) is
     * still reported here but then re-thrown rather than swallowed: it signals the JVM itself is in
     * a corrupted state, and continuing to run the pod with no crash signal for Kubernetes to act on
     * would be a behavior change beyond diagnostics, not just a logging one.
     */
    public void exportarAgora() {
        try {
            List<Map<String, Object>> eventos;
            try {
                eventos = capturar();
            } catch (RuntimeException failure) {
                registrarFalha("SNAPSHOT_CAPTURE_FAILED");
                return;
            }
            int capturedCount = eventos.size();
            try {
                exporter.exportar(eventos);
            } catch (RuntimeException failure) {
                registrarFalha("SNAPSHOT_EXPORT_FAILED");
                return;
            }
            registrarSucesso(capturedCount, capturedCount);
        } catch (VirtualMachineError failure) {
            registrarFalhaNaoTratada();
            throw failure;
        } catch (Throwable failure) {
            registrarFalhaNaoTratada();
        }
    }

    private void registrarFalha(String errorCode) {
        // Structured logging policy supplies correlation/version; never include provider response or report data.
        MDC.put("event_name", "snapshot_export_failed");
        MDC.put("error_code", errorCode);
        try {
            LOG.warn("");
        } finally {
            MDC.remove("event_name");
            MDC.remove("error_code");
        }
    }

    private void registrarSucesso(int capturedCount, int exportedCount) {
        // Per-tick signal that lets an operator distinguish "runs and posts" from a dead repeating
        // task; counts only, never event payloads.
        MDC.put("event_name", "snapshot_tick_completed");
        MDC.put("events_captured_count", Integer.toString(capturedCount));
        MDC.put("events_exported_count", Integer.toString(exportedCount));
        try {
            LOG.info("");
        } finally {
            MDC.remove("event_name");
            MDC.remove("events_captured_count");
            MDC.remove("events_exported_count");
        }
    }

    private void registrarFalhaNaoTratada() {
        // Anything neither inner catch handles (e.g. an Error) must still be contained here so the
        // scheduleWithFixedDelay task never dies silently.
        MDC.put("event_name", "snapshot_tick_crashed");
        MDC.put("error_code", "SNAPSHOT_TICK_UNHANDLED");
        try {
            LOG.error("");
        } finally {
            MDC.remove("event_name");
            MDC.remove("error_code");
        }
    }

    /** Visible for deterministic local verification; queries finish before this returns its bounded event list. */
    public List<Map<String, Object>> capturar() {
        Instant capturedAt = clock.instant();
        LocalDate today = capturedAt.atZone(zone).toLocalDate();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int offset = 6; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            events.add(reportEvent(reports.consultar(day, day.plusDays(1), zone), capturedAt, day, day.plusDays(1), "day"));
        }
        LocalDate rollingStart = today.minusDays(6);
        events.add(reportEvent(reports.consultar(rollingStart, today.plusDays(1), zone), capturedAt,
                rollingStart, today.plusDays(1), "rolling_7_day"));
        for (StatusAtual current : reports.statusAtual(capturedAt)) {
            events.add(statusEvent(current, capturedAt));
        }
        SnapshotDiagnostics currentDiagnostics = diagnostics.get();
        events.add(outboxEvent(currentDiagnostics, capturedAt));
        events.add(heartbeatEvent(capturedAt, currentDiagnostics));
        return List.copyOf(events);
    }

    private Map<String, Object> reportEvent(RelatorioPeriodo report, Instant capturedAt,
                                            LocalDate start, LocalDate end, String kind) {
        Map<String, Object> event = base("WorkshopReportSnapshot", capturedAt);
        event.put("window_kind", kind);
        event.put("period_start", start.toString());
        event.put("period_end_exclusive", end.toString());
        if (kind.equals("day")) event.put("business_date", start.toString());
        event.put("created_count", report.criadas());
        event.put("eligible_count", report.elegiveis());
        event.put("excluded_count", report.excluidas());
        event.put("snapshot_id", environment + ":" + kind + ":" + start + ":" + end + ":" + capturedAt.toEpochMilli());
        Map<StatusOrdemServico, DuracaoStatus> durations = new EnumMap<>(StatusOrdemServico.class);
        durations.putAll(report.duracoes());
        putDuration(event, "diagnosis", durations.get(StatusOrdemServico.EM_DIAGNOSTICO));
        putDuration(event, "execution", durations.get(StatusOrdemServico.EM_EXECUCAO));
        putDuration(event, "finalization", durations.get(StatusOrdemServico.FINALIZADA));
        return Map.copyOf(event);
    }

    private static void putDuration(Map<String, Object> event, String prefix, DuracaoStatus duration) {
        BigDecimal total = duration == null || duration.totalSegundos() == null ? BigDecimal.ZERO : duration.totalSegundos();
        long samples = duration == null ? 0 : duration.amostras();
        event.put(prefix + "_total_seconds", total);
        event.put(prefix + "_samples", samples);
    }

    private Map<String, Object> statusEvent(StatusAtual status, Instant capturedAt) {
        Map<String, Object> event = base("WorkshopStatusSnapshot", capturedAt);
        event.put("status", status.status().name());
        event.put("current_count", status.quantidade());
        event.put("known_age_count", status.amostrasIdade());
        event.put("unknown_age_count", status.idadesDesconhecidas());
        event.put("max_age_seconds", status.idadeMaximaSegundos());
        event.put("snapshot_id", environment + ":status:" + status.status() + ":" + capturedAt.toEpochMilli());
        return Map.copyOf(event);
    }

    private Map<String, Object> outboxEvent(SnapshotDiagnostics value, Instant capturedAt) {
        Map<String, Object> event = base("WorkshopOutboxHealth", capturedAt);
        event.put("pending_count", value.outboxPending());
        event.put("blocked_count", value.outboxBlocked());
        event.put("oldest_pending_seconds", value.outboxOldestSeconds());
        event.put("snapshot_id", environment + ":outbox:" + capturedAt.toEpochMilli());
        return Map.copyOf(event);
    }

    private Map<String, Object> heartbeatEvent(Instant capturedAt, SnapshotDiagnostics value) {
        Map<String, Object> event = base("WorkshopTelemetryHeartbeat", capturedAt);
        event.put("drop_count", Math.max(value.droppedExports(), droppedExports.getAsLong()));
        event.put("snapshot_id", environment + ":heartbeat:" + capturedAt.toEpochMilli());
        return Map.copyOf(event);
    }

    private Map<String, Object> base(String type, Instant capturedAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", type);
        event.put("environment", environment);
        // Event API preserves this capture time; dashboards must order by it, never ingestion time.
        event.put("timestamp", capturedAt.toEpochMilli());
        event.put("captured_at", capturedAt.toEpochMilli());
        return event;
    }

    private static String requireEnvironment(String value) {
        if (!List.of("staging", "production").contains(value)) {
            throw new IllegalArgumentException("environment must be staging or production");
        }
        return value;
    }
}
