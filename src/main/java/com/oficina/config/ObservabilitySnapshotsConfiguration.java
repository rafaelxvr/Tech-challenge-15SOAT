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
        private final SnapshotScheduler scheduler;
        private ScheduledExecutorService executor;
        SnapshotPoller(SnapshotScheduler scheduler) { this.scheduler = scheduler; }
        @Override public synchronized void start() {
            if (isRunning()) return;
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "snapshot-exporter"); thread.setDaemon(true); return thread;
            });
            executor.scheduleWithFixedDelay(scheduler::exportarAgora,
                    java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 1001), 60, TimeUnit.SECONDS);
        }
        @Override public synchronized void stop() { if (executor != null) executor.shutdownNow(); }
        @Override public synchronized boolean isRunning() { return executor != null && !executor.isShutdown(); }
    }
}
