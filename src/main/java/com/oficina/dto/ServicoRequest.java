package com.oficina.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record ServicoRequest(
        @NotBlank @Size(max = 255) String nome,
        String descricao,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal valor,
        @NotNull @Min(1) Integer tempoEstimadoMin
) {}
