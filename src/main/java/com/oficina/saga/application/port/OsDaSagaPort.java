package com.oficina.saga.application.port;

import com.oficina.saga.domain.MotivoFalha;

import java.math.BigDecimal;
import java.util.UUID;

/** os-service's own aggregates as seen by the saga. Every call joins the caller's transaction. */
public interface OsDaSagaPort {
    void iniciarDiagnostico(UUID osId);
    void enviarOrcamento(UUID osId, BigDecimal valorOrcado);
    void aguardarPagamento(UUID osId);
    void voltarParaDiagnostico(UUID osId);
    void iniciarExecucao(UUID osId);
    void finalizar(UUID osId);
    void cancelar(UUID osId, MotivoFalha motivo);
    /** Deducts every part only if all are available; returns false and changes nothing otherwise. */
    boolean reservarPecas(UUID osId);
    void reporPecas(UUID osId);
    SnapshotOs snapshot(UUID osId);
}
