package com.oficina.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record PecaRequest(
        @NotBlank @Size(max = 50) String codigo,
        @NotBlank @Size(max = 255) String nome,
        String descricao,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal valorUnitario,
        @NotNull @Min(0) Integer quantidadeEstoque,
        @NotNull @Min(0) Integer quantidadeMinima,
        @NotBlank @Size(max = 20) String unidadeMedida
) {}
