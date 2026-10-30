package com.oficina.saga.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SagaDetalhe(UUID sagaId, UUID osId, String estado, String etapa, int versaoOrcamento, Instant prazo,
                          String motivoFalha, List<String> compensacoesPendentes, List<Passo> passos) {
    public record Passo(int ordem, String etapa, String evento, String estado, Instant ocorridoEm) {}
}
