package com.oficina.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Entrada legada autenticada: documento apenas confirma o proprietário já identificado pelo JWT.
 */
public record DecisaoOrcamentoRequest(
        @NotNull DecisaoOrcamento decisao,
        @NotBlank String documentoCliente,
        String observacao
) {
    public enum DecisaoOrcamento {
        APROVADO,
        RECUSADO
    }
}
