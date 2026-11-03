package com.oficina.saga.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** What other services may know about an order: references and priced lines, no contact data or notes. */
public record SnapshotOs(UUID osId, long numero, UUID clienteId, List<ItemOs> itens) {
    public record ItemOs(String tipo, UUID referenciaId, String descricao, int quantidade, BigDecimal precoUnitario) {}

    public SnapshotOs {
        itens = List.copyOf(itens);
    }
}
