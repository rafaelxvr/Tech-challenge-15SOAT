package com.oficina.dto;

import com.oficina.entity.StatusOrdemServico;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AcompanhamentoOsResponse(
        Long numero,
        StatusOrdemServico status,
        BigDecimal valorTotal,
        LocalDateTime criadoEm,
        LocalDateTime ultimaAtualizacaoStatusEm,
        String clienteNome,
        String placa,
        List<OrdemServicoDetalheResponse.HistoricoStatusResponse> historico
) {}
