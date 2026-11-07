package com.oficina.adapter.out.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPublicacaoTest {
    static final Instant T = Instant.parse("2026-09-15T12:00:00Z");

    @Test void primeiraFalhaEsperaCincoSegundosSemJitter() {
        var t = new BackoffPublicacao(bound -> 0).proxima(0, T);
        assertThat(t.numero()).isEqualTo(1);
        assertThat(t.bloqueada()).isFalse();
        assertThat(t.disponivelEm()).isEqualTo(T.plusSeconds(5));
    }

    @Test void jitterRespeitaTetoDeCincoMinutos() {
        var t = new BackoffPublicacao(bound -> bound - 1).proxima(10, T);
        assertThat(t.numero()).isEqualTo(11);
        assertThat(t.disponivelEm()).isEqualTo(T.plusMillis(300_000));
    }

    @Test void decimaSegundaTentativaBloqueia() {
        var t = new BackoffPublicacao(bound -> 0).proxima(11, T);
        assertThat(t.numero()).isEqualTo(12);
        assertThat(t.bloqueada()).isTrue();
        assertThat(new BackoffPublicacao(bound -> 0).proxima(40, T).numero()).isEqualTo(12);
    }
}
