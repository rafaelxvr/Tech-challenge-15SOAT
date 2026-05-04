package com.oficina.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ItemPecaOsRequest(
        @NotNull UUID pecaId,
        @NotNull @Min(1) Integer quantidade
) {}
