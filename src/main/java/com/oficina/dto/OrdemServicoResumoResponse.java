package com.oficina.dto;

import com.oficina.entity.StatusOrdemServico;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record OrdemServicoResumoResponse(
        UUID id,
        Long numero,
        StatusOrdemServico status,
        String clienteNome,
        String placa,
        BigDecimal valorTotal,
        LocalDateTime criadoEm
) {}
