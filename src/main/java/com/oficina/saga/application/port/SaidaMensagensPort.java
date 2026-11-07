package com.oficina.saga.application.port;

import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.domain.Destino;

/** Records an outgoing message in the caller's transaction; delivery happens after commit. */
public interface SaidaMensagensPort {
    void registrar(Destino destino, Envelope envelope);
}
