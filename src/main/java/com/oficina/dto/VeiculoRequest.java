package com.oficina.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record VeiculoRequest(
        @NotBlank String placa,
        @NotBlank @Size(max = 100) String marca,
        @NotBlank @Size(max = 100) String modelo,
        @NotNull @Min(1900) @Max(2100) Integer ano,
        @Size(max = 50) String cor,
        @Size(max = 17) String chassi,
        @NotNull UUID clienteId
) {}
