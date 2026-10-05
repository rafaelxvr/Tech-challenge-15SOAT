package com.oficina.saga.application;

import com.oficina.application.port.out.CicloOsPort;

import java.util.Optional;
import java.util.UUID;

/**
 * Null Object used while the saga switch is off: no order is ever driven by the saga, so the
 * service-order use cases keep the Phase 3 manual flow and never touch the saga tables.
 */
public final class CicloOsManual implements CicloOsPort {
    @Override public void iniciar(UUID osId) { /* manual flow: nothing to start */ }

    @Override public boolean conduzidaPorSaga(UUID osId) { return false; }

    /** Unreachable: decisions are routed here only for orders driven by the saga. */
    @Override public void registrarDecisao(UUID osId, boolean aprovado) {
        throw new IllegalStateException("Saga desligada: nenhuma ordem é conduzida pela saga.");
    }

    @Override public Optional<ResumoSaga> resumo(UUID osId) { return Optional.empty(); }
}
