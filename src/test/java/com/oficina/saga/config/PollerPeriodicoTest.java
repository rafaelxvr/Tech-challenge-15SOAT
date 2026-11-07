package com.oficina.saga.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;

class PollerPeriodicoTest {
    private void verificarQueTickSeguinteRoda(Runnable falha) {
        AtomicInteger execucoes = new AtomicInteger();
        PollerPeriodico poller = new PollerPeriodico("teste", () -> {
            if (execucoes.incrementAndGet() == 1) falha.run();
        }, Duration.ofMillis(20));
        poller.start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> execucoes.get() >= 3);
        } finally {
            poller.stop();
        }
    }

    @Test void runtimeExceptionNaoParaOsTicksSeguintes() {
        verificarQueTickSeguinteRoda(() -> { throw new IllegalStateException("x"); });
    }

    @Test void errorNaoParaOsTicksSeguintes() {
        verificarQueTickSeguinteRoda(() -> { throw new AssertionError(); });
    }
}
