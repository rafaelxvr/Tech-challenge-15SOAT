package com.oficina.dto;

import com.oficina.entity.StatusOrdemServico;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrdemServicoDetalheResponse(
        UUID id,
        Long numero,
        StatusOrdemServico status,
        BigDecimal valorTotal,
        String observacoes,
        LocalDateTime aprovadoEm,
        LocalDateTime iniciadoEm,
        LocalDateTime finalizadoEm,
        LocalDateTime entregueEm,
        LocalDateTime criadoEm,
        ClienteResumo cliente,
        VeiculoResumo veiculo,
        List<ServicoLinhaResponse> servicos,
        List<PecaLinhaResponse> pecas,
        List<HistoricoStatusResponse> historico
) {
    public record ClienteResumo(UUID id, String nome, String documento) {}

    public record VeiculoResumo(UUID id, String placa, String marca, String modelo, Integer ano) {}

    public record ServicoLinhaResponse(
            UUID id,
            UUID servicoId,
            String nomeServico,
            Integer quantidade,
            BigDecimal valorUnitario,
            BigDecimal valorTotal,
            String observacao
    ) {}

    public record PecaLinhaResponse(
            UUID id,
            UUID pecaId,
            String codigoPeca,
            String nomePeca,
            Integer quantidade,
            BigDecimal valorUnitario,
            BigDecimal valorTotal
    ) {}

    public record HistoricoStatusResponse(
            StatusOrdemServico statusAnterior,
            StatusOrdemServico statusNovo,
            String observacao,
            LocalDateTime criadoEm,
            Instant ocorridoEm
    ) {}
}
