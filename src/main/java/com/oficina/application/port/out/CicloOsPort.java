package com.oficina.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** How the service-order use cases reach the distributed saga without depending on its internals. */
public interface CicloOsPort {
    void iniciar(UUID osId);
    boolean conduzidaPorSaga(UUID osId);
    void registrarDecisao(UUID osId, boolean aprovado);
    Optional<ResumoSaga> resumo(UUID osId);

    record ResumoSaga(String estado, String checkoutUrl) {}
}
