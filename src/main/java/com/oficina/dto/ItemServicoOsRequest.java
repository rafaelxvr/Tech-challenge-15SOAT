package com.oficina.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ItemServicoOsRequest(
        @NotNull UUID servicoId,
        @NotNull @Min(1) Integer quantidade,
        String observacao
) {}
