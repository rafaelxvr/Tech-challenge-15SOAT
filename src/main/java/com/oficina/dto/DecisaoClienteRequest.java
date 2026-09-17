package com.oficina.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Identity comes exclusively from the validated customer principal. */
public record DecisaoClienteRequest(
        @NotNull DecisaoOrcamentoRequest.DecisaoOrcamento decisao,
        @Size(max = 2000) String observacao
) {}
