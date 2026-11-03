package com.oficina.config;

import com.oficina.application.observability.SnapshotDiagnostics;
import com.oficina.application.observability.SnapshotScheduler;
import com.oficina.application.relatorio.RelatorioPeriodo;
import com.oficina.application.relatorio.RelatoriosPort;
import com.oficina.application.relatorio.StatusAtual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the UNIT that {@code SnapshotPoller.start()} passes to {@code scheduleWithFixedDelay} for the
 * startup jitter. {@code nextLong(0, 1001)} was written to mean milliseconds - a sub-second stagger so
 * replicas do not publish in lockstep - but a single {@code TimeUnit.SECONDS} applied to both the
 * jitter and the 60-second period made the first tick fire anywhere from 0 to ~16.7 minutes after
 * startup, leaving telemetry blind after every deploy.
 *
 * <p>A test that only checks "some task got scheduled with roughly the right numbers" would still
 * pass with {@code TimeUnit.SECONDS} left in place, since 0-1000 is a plausible initial delay in
 * either unit. This test instead captures the exact {@link TimeUnit} handed to the scheduler and
 * fails unless it is genuinely milliseconds while the resulting period is still 60 seconds.
 */
class SnapshotPollerJitterUnitTest {

    private ObservabilitySnapshotsConfiguration.SnapshotPoller poller;

    @AfterEach
    void cleanup() {
        if (poller != null) poller.stop();
    }

    @Test
    void startSchedulesSubSecondJitterInMillisecondsWithUnchangedSixtySecondPeriod() {
        CapturingScheduledExecutor executor = new CapturingScheduledExecutor();
        poller = new ObservabilitySnapshotsConfiguration.SnapshotPoller(noOpScheduler(), () -> executor);

        poller.start();

        assertThat(executor.capturedUnit)
                .as("initial delay and period must be expressed in milliseconds, not seconds")
                .isEqualTo(TimeUnit.MILLISECONDS);
        assertThat(executor.capturedInitialDelay)
                .as("jitter must be a sub-second stagger (0-1000 ms), not 0-1000 seconds")
                .isBetween(0L, 1000L);
        assertThat(executor.capturedUnit.toSeconds(executor.capturedPeriod))
                .as("the 60-second cadence must be unchanged")
                .isEqualTo(60L);
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

    /**
     * Captures the arguments {@code SnapshotPoller.start()} passes to {@code scheduleWithFixedDelay}
     * without ever running the scheduled task, so the test is deterministic and does not depend on
     * wall-clock timing. Lifecycle calls ({@code shutdownNow}, {@code isShutdown}) delegate to a real
     * single-thread executor so {@code stop()}/{@code isRunning()} still behave correctly.
     */
    private static final class CapturingScheduledExecutor extends AbstractExecutorService
            implements ScheduledExecutorService {
        private final ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
        private long capturedInitialDelay;
        private long capturedPeriod;
        private TimeUnit capturedUnit;

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            this.capturedInitialDelay = initialDelay;
            this.capturedPeriod = delay;
            this.capturedUnit = unit;
            // Never actually run `command`; schedule a harmless no-op far in the future instead.
            return delegate.schedule(() -> { }, Long.MAX_VALUE, TimeUnit.DAYS);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return delegate.schedule(command, delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return delegate.schedule(callable, delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return delegate.scheduleAtFixedRate(command, initialDelay, period, unit);
        }

        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
        @Override public void execute(Runnable command) { delegate.execute(command); }
    }
}
