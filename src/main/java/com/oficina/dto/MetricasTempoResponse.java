package com.oficina.dto;

public record MetricasTempoResponse(
        double tempoMedioEstimadoServicosMinutos,
        double tempoMedioExecucaoRealMinutos,
        long ordensComTempoMedido
) {}
