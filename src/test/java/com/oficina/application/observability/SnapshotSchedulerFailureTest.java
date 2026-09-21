package com.oficina.application.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import com.oficina.application.relatorio.RelatorioPeriodo;
import com.oficina.application.relatorio.RelatoriosPort;
import com.oficina.application.relatorio.StatusAtual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotSchedulerFailureTest {

    private final Logger schedulerLogger = (Logger) LoggerFactory.getLogger(SnapshotScheduler.class);
    private final List<Appender<ILoggingEvent>> attachedAppenders = new ArrayList<>();

    @AfterEach
    void detachAppenders() {
        attachedAppenders.forEach(schedulerLogger::detachAppender);
        attachedAppenders.clear();
        MDC.clear();
    }

    @Test
    void distinguishesCaptureFailureFromExportFailure() {
        AtomicReference<String> observed = new AtomicReference<>();

        SnapshotScheduler capturaQuebrada = schedulerWith(
                () -> { throw new IllegalStateException("db down"); },
                eventos -> { }, observed);
        capturaQuebrada.exportarAgora();
        assertThat(observed.get()).isEqualTo("SNAPSHOT_CAPTURE_FAILED");

        SnapshotScheduler exportQuebrado = schedulerWith(
                () -> List.of(Map.of("eventType", "X", "environment", "staging", "timestamp", 1L)),
                eventos -> { throw new IllegalStateException("endpoint rejected"); }, observed);
        exportQuebrado.exportarAgora();
        assertThat(observed.get()).isEqualTo("SNAPSHOT_EXPORT_FAILED");
    }

    /**
     * Builds a scheduler through the real constructor. {@code captura} is consulted from the fake
     * {@link RelatoriosPort}'s {@code statusAtual} call, the last read the production capture phase
     * makes before assembling events: throwing there fails capture before {@code exporter} is ever
     * reached, and returning normally lets capture succeed so {@code exporter} runs next. The
     * observed {@code error_code} is captured straight off the log event's MDC via a Logback
     * appender attached to {@link SnapshotScheduler}'s own logger, the same pattern used by
     * {@code NewRelicOrderTelemetryTest}.
     */
    private SnapshotScheduler schedulerWith(Supplier<List<Map<String, Object>>> captura,
                                             SnapshotExporter exporter,
                                             AtomicReference<String> observed) {
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                observed.set(event.getMDCPropertyMap().get("error_code"));
            }
        };
        appender.start();
        schedulerLogger.addAppender(appender);
        attachedAppenders.add(appender);

        RelatoriosPort reports = new RelatoriosPort() {
            @Override
            public RelatorioPeriodo consultar(LocalDate inicio, LocalDate fimExclusive, ZoneId zona) {
                return new RelatorioPeriodo(0, 0, 0, Map.of());
            }

            @Override
            public List<StatusAtual> statusAtual(Instant agora) {
                captura.get();
                return List.of();
            }
        };

        return new SnapshotScheduler(reports, exporter,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                "staging", ZoneOffset.UTC,
                () -> SnapshotDiagnostics.unavailable(0), () -> 0L);
    }
}
