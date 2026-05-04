package com.oficina.dto;

import java.util.UUID;

public record VeiculoResponse(
        UUID id,
        String placa,
        String marca,
        String modelo,
        Integer ano,
        String cor,
        String chassi,
        UUID clienteId,
        boolean ativo
) {}
