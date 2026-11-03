package com.oficina.saga.application.port;

import com.oficina.saga.domain.Destino;

import java.util.UUID;

/** One bounded FIFO send; returns the broker acknowledgement id. */
@FunctionalInterface
public interface EnviadorMensagens {
    String enviar(Destino destino, UUID osId, UUID messageId, String corpo);
}
