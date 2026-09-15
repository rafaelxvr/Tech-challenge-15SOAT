package com.oficina.application.notificacao;

/** One bounded FIFO send. Returns the queue acknowledgement ID. */
@FunctionalInterface
public interface PublicadorFila {
    String enviar(StatusOrdemServicoRegistrado evento);
}
