package com.oficina.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.adapter.out.observability.JdbcSnapshotDiagnostics;
import com.oficina.adapter.out.observability.NewRelicSnapshotExporter;
import com.oficina.application.observability.SnapshotScheduler;
import com.oficina.application.relatorio.RelatoriosPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Opt-in vendor wiring. Endpoint/account/key are deployment inputs and never appear in application logs. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "oficina.observability.snapshots.enabled", havingValue = "true")
public class ObservabilitySnapshotsConfiguration {
    @Bean NewRelicSnapshotExporter newRelicSnapshotExporter(
            @Value("${oficina.observability.snapshots.endpoint}") String endpoint,
            @Value("${oficina.observability.snapshots.account-id}") String accountId,
            @Value("${NEW_RELIC_INSERT_KEY}") String ingestKey, ObjectMapper mapper) {
        return new NewRelicSnapshotExporter(URI.create(endpoint), accountId, ingestKey,
                new NewRelicSnapshotExporter.JdkHttpTransport(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2)).build()), mapper);
    }

    @Bean JdbcSnapshotDiagnostics snapshotDiagnostics(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                                       Clock clock) {
        return new JdbcSnapshotDiagnostics(jdbc, transactionManager, clock);
    }

    @Bean SnapshotScheduler snapshotScheduler(RelatoriosPort reports, NewRelicSnapshotExporter exporter,
                                              JdbcSnapshotDiagnostics diagnostics, Clock clock,
                                              @Value("${oficina.observability.snapshots.environment}") String environment,
                                              @Value("${oficina.observability.snapshots.zone:America/Sao_Paulo}") String zone) {
        return new SnapshotScheduler(reports, exporter, clock, environment, ZoneId.of(zone),
                () -> diagnostics.current(exporter.droppedBatches()), exporter::droppedBatches);
    }

    @Bean SmartLifecycle snapshotPoller(SnapshotScheduler scheduler) {
        return new SnapshotPoller(scheduler);
    }

    /** Fixed 60-second cadence with at most one second random start jitter; no backlog or retry loop. */
    static final class SnapshotPoller implements SmartLifecycle {
        private static final Logger LOG = LoggerFactory.getLogger(SnapshotPoller.class);
        private final SnapshotScheduler scheduler;
        private final java.util.function.Supplier<ScheduledExecutorService> executorFactory;
        private ScheduledExecutorService executor;

        SnapshotPoller(SnapshotScheduler scheduler) {
            this(scheduler, () -> Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "snapshot-exporter");
                thread.setDaemon(true);
                return thread;
            }));
        }

        /** Visible for tests: lets a test observe exactly what start() passes to
         * scheduleWithFixedDelay (in particular the TimeUnit) without depending on wall-clock timing. */
        SnapshotPoller(SnapshotScheduler scheduler, java.util.function.Supplier<ScheduledExecutorService> executorFactory) {
            this.scheduler = scheduler;
            this.executorFactory = executorFactory;
        }

        @Override public synchronized void start() {
            if (isRunning()) return;
            executor = executorFactory.get();
            // Logged before scheduling so the "started" signal can never race a tick: with the jitter
            // now correctly expressed in milliseconds, a zero draw lets the first tick fire almost
            // immediately, and scheduleWithFixedDelay runs on the executor's own thread rather than
            // this one, so registering the tick before logging its start could otherwise let a tick
            // complete first.
            registrarInicio();
            // Both arguments are milliseconds: a 0-1000ms startup jitter so replicas don't all
            // publish in the same instant, then a steady 60-second (60_000ms) cadence. Expressing
            // both in the same unit here is deliberate - mixing a millisecond jitter with a
            // TimeUnit.SECONDS call previously turned the intended sub-second stagger into a
            // 0-1000 SECOND delay before the first tick.
            executor.scheduleWithFixedDelay(scheduler::exportarAgora,
                    java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 1001),
                    TimeUnit.SECONDS.toMillis(60), TimeUnit.MILLISECONDS);
        }
        private void registrarInicio() {
            // Synchronous signal that the poller bean started, independent of whether any tick ever
            // fires; this is what lets an operator tell "never started" apart from the other states.
            MDC.put("event_name", "snapshot_poller_started");
            try {
                LOG.info("");
            } finally {
                MDC.remove("event_name");
            }
        }
        @Override public synchronized void stop() { if (executor != null) executor.shutdownNow(); }
        @Override public synchronized boolean isRunning() { return executor != null && !executor.isShutdown(); }
    }
}
