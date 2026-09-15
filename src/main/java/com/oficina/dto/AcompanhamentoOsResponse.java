package com.oficina.dto;

import com.oficina.entity.StatusOrdemServico;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

public record AcompanhamentoOsResponse(
        Long numero,
        StatusOrdemServico status,
        BigDecimal valorTotal,
        LocalDateTime criadoEm,
        LocalDateTime ultimaAtualizacaoStatusEm,
        List<LinhaOrcamento> servicos,
        List<LinhaOrcamento> pecas,
        List<HistoricoCliente> historico
) {
    public record LinhaOrcamento(String descricao, Integer quantidade,
                                BigDecimal valorUnitario, BigDecimal valorTotal) {}
    /** Free-form internal notes and actor identifiers are never part of the customer projection. */
    public record HistoricoCliente(StatusOrdemServico statusAnterior, StatusOrdemServico statusNovo,
                                   LocalDateTime criadoEm, Instant ocorridoEm) {}
}
