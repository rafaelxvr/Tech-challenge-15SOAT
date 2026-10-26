package com.oficina.saga.domain;

import java.time.Instant;
import java.util.Objects;

public record MomentoSaga(Instant agora, PrazosSaga prazos) {
    public MomentoSaga {
        Objects.requireNonNull(agora, "agora");
        Objects.requireNonNull(prazos, "prazos");
    }
}
