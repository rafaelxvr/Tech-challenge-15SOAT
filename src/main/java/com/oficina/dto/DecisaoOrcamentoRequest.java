package com.oficina.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Notificação externa de aprovação ou recusa do orçamento (cliente / sistema parceiro).
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
