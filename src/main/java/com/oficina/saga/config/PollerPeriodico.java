package com.oficina.saga.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One daemon thread per task and pod, fixed delay between runs. No cron framework. */
public class PollerPeriodico implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(PollerPeriodico.class);
    private final String nome;
    private final Runnable tarefa;
    private final Duration intervalo;
    private ScheduledExecutorService executor;

    public PollerPeriodico(String nome, Runnable tarefa, Duration intervalo) {
        this.nome = nome;
        this.tarefa = tarefa;
        this.intervalo = intervalo;
    }

    @Override public synchronized void start() {
        if (isRunning()) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, nome);
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            executarContendoFalhas();
        }, intervalo.toMillis(), intervalo.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void executarContendoFalhas() {
        try {
            tarefa.run();
        } catch (Throwable e) { // an escaped Throwable would silently cancel the schedule
            MDC.put("event_name", "poller_falhou");
            MDC.put("poller", nome);
            MDC.put("error_code", e.getClass().getSimpleName());
            try { LOG.warn(""); } finally { MDC.remove("event_name"); MDC.remove("poller"); MDC.remove("error_code"); }
        }
    }

    @Override public synchronized void stop() { if (executor != null) executor.shutdownNow(); }

    @Override public synchronized boolean isRunning() { return executor != null && !executor.isShutdown(); }
}
