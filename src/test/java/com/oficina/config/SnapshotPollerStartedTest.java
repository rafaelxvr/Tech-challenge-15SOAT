package com.oficina.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import com.oficina.application.observability.SnapshotDiagnostics;
import com.oficina.application.observability.SnapshotScheduler;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the poller emits a start signal from its own logger the instant {@code start()} runs,
 * independent of whether its scheduled ticks ever fire. This is the signal that lets an operator
 * tell "the poller bean never started" apart from the other two silent-failure states, since it is
 * logged synchronously inside {@code SmartLifecycle.start()} rather than from inside a tick.
 */
class SnapshotPollerStartedTest {

    private final Logger pollerLogger =
            (Logger) LoggerFactory.getLogger(ObservabilitySnapshotsConfiguration.SnapshotPoller.class);
    private final List<Appender<ILoggingEvent>> attachedAppenders = new ArrayList<>();
    private ObservabilitySnapshotsConfiguration.SnapshotPoller poller;

    @AfterEach
    void cleanup() {
        if (poller != null) poller.stop();
        attachedAppenders.forEach(pollerLogger::detachAppender);
        attachedAppenders.clear();
        MDC.clear();
    }

    @Test
    void startLogsPollerStartedBeforeAnyTickCanRun() {
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if ("snapshot_poller_started".equals(event.getMDCPropertyMap().get("event_name"))) {
                    observed.set(Map.copyOf(event.getMDCPropertyMap()));
                }
            }
        };
        appender.start();
        pollerLogger.addAppender(appender);
        attachedAppenders.add(appender);

        SnapshotScheduler scheduler = noOpScheduler();
        poller = new ObservabilitySnapshotsConfiguration.SnapshotPoller(scheduler);

        poller.start();

        assertThat(poller.isRunning()).isTrue();
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().get("event_name")).isEqualTo("snapshot_poller_started");
    }

    private SnapshotScheduler noOpScheduler() {
        RelatoriosPort reports = new RelatoriosPort() {
            @Override
            public RelatorioPeriodo consultar(LocalDate inicio, LocalDate fimExclusive, ZoneId zona) {
                return new RelatorioPeriodo(0, 0, 0, Map.of());
            }

            @Override
            public List<StatusAtual> statusAtual(Instant agora) {
                return List.of();
            }
        };
        return new SnapshotScheduler(reports, eventos -> { },
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                "staging", ZoneOffset.UTC,
                () -> SnapshotDiagnostics.unavailable(0), () -> 0L);
    }
}
