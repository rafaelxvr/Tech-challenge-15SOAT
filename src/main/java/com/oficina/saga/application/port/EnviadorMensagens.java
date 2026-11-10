package com.oficina.saga.application.port;

import com.oficina.saga.domain.Destino;

import java.util.Map;
import java.util.UUID;

/** One bounded FIFO send; {@code traco} is the sanitized trace carrier persisted with the message. Returns the broker acknowledgement id. */
@FunctionalInterface
public interface EnviadorMensagens {
    String enviar(Destino destino, UUID osId, UUID messageId, String corpo, Map<String, String> traco);
}
