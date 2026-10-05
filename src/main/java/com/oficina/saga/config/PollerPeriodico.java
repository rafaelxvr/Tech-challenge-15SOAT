package com.oficina.saga.config;

import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One daemon thread per task and pod, fixed delay between runs. No cron framework. */
public class PollerPeriodico implements SmartLifecycle {
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
            try { tarefa.run(); } catch (RuntimeException ignored) { /* next tick retries; task logs its own failure */ }
        }, intervalo.toMillis(), intervalo.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override public synchronized void stop() { if (executor != null) executor.shutdownNow(); }

    @Override public synchronized boolean isRunning() { return executor != null && !executor.isShutdown(); }
}
