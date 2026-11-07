package com.oficina.saga.domain;

import java.util.Objects;
import java.util.UUID;

public record ComandoSaga(UUID messageId, Comando comando, EtapaSaga etapa) {
    public ComandoSaga {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(comando, "comando");
        Objects.requireNonNull(etapa, "etapa");
    }
}
