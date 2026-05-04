package com.oficina.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PecaResponse(
        UUID id,
        String codigo,
        String nome,
        String descricao,
        BigDecimal valorUnitario,
        Integer quantidadeEstoque,
        Integer quantidadeMinima,
        String unidadeMedida,
        boolean ativo
) {}
