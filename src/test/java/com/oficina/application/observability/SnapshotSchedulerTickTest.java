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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the tick-level diagnostics that let an operator tell, from pod logs alone, that the
 * poller's repeating task is actually completing ticks (state distinguishing "it runs and posts"
 * from "the repeating task died silently"), and that a tick throwing something the two existing
 * capture/export {@code catch (RuntimeException ...)} blocks do not catch (e.g. an {@link Error})
 * is contained rather than propagated, since {@code scheduleWithFixedDelay} permanently cancels a
 * task whose run throws. The one exception is a {@link VirtualMachineError}: that is still
 * reported, but then re-thrown rather than swallowed, since continuing to run the pod after a
 * genuine VM failure would hide the crash signal Kubernetes needs to act on.
 */
class SnapshotSchedulerTickTest {

    private final Logger schedulerLogger = (Logger) LoggerFactory.getLogger(SnapshotScheduler.class);
    private final List<Appender<ILoggingEvent>> attachedAppenders = new ArrayList<>();

    @AfterEach
    void detachAppenders() {
        attachedAppenders.forEach(schedulerLogger::detachAppender);
        attachedAppenders.clear();
        MDC.clear();
    }

    @Test
    void completedTickReportsCapturedAndExportedCounts() {
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        SnapshotScheduler scheduler = schedulerWith(
                () -> { }, eventos -> { }, "snapshot_tick_completed", observed);

        scheduler.exportarAgora();

        Map<String, String> mdc = observed.get();
        assertThat(mdc).isNotNull();
        assertThat(mdc.get("event_name")).isEqualTo("snapshot_tick_completed");
        int capturedCount = Integer.parseInt(mdc.get("events_captured_count"));
        int exportedCount = Integer.parseInt(mdc.get("events_exported_count"));
        assertThat(capturedCount).isGreaterThan(0);
        assertThat(exportedCount).isEqualTo(capturedCount);
    }

    @Test
    void tickThrowingUncaughtErrorIsContainedAndReportedRatherThanPropagated() {
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        SnapshotScheduler scheduler = schedulerWith(
                () -> { throw new AssertionError("unexpected wiring bug"); }, eventos -> { },
                "snapshot_tick_crashed", observed);

        assertThatCode(scheduler::exportarAgora).doesNotThrowAnyException();

        Map<String, String> mdc = observed.get();
        assertThat(mdc).isNotNull();
        assertThat(mdc.get("event_name")).isEqualTo("snapshot_tick_crashed");
        assertThat(mdc.get("error_code")).isEqualTo("SNAPSHOT_TICK_UNHANDLED");
    }

    @Test
    void tickThrowingVirtualMachineErrorIsReportedThenPropagated() {
        AtomicReference<Map<String, String>> observed = new AtomicReference<>();
        SnapshotScheduler scheduler = schedulerWith(
                () -> { throw new InternalError("jvm in a corrupted state"); }, eventos -> { },
                "snapshot_tick_crashed", observed);

        assertThatThrownBy(scheduler::exportarAgora).isInstanceOf(VirtualMachineError.class);

        Map<String, String> mdc = observed.get();
        assertThat(mdc).isNotNull();
        assertThat(mdc.get("event_name")).isEqualTo("snapshot_tick_crashed");
        assertThat(mdc.get("error_code")).isEqualTo("SNAPSHOT_TICK_UNHANDLED");
    }

    /**
     * Builds a scheduler through the real constructor, the same approach as
     * {@link SnapshotSchedulerFailureTest#schedulerWith}. {@code duringCapture} runs from the fake
     * {@link RelatoriosPort}'s {@code statusAtual} call, letting a test inject an {@link Error} at
     * the last read the capture phase makes. The appender only records MDC snapshots for the event
     * named {@code eventNameOfInterest}, so an unrelated log line from the same tick cannot mask it.
     */
    private SnapshotScheduler schedulerWith(Runnable duringCapture, SnapshotExporter exporter,
                                             String eventNameOfInterest,
                                             AtomicReference<Map<String, String>> observed) {
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (eventNameOfInterest.equals(event.getMDCPropertyMap().get("event_name"))) {
                    observed.set(Map.copyOf(event.getMDCPropertyMap()));
                }
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
                duringCapture.run();
                return List.of();
            }
        };

        return new SnapshotScheduler(reports, exporter,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                "staging", ZoneOffset.UTC,
                () -> SnapshotDiagnostics.unavailable(0), () -> 0L);
    }
}
