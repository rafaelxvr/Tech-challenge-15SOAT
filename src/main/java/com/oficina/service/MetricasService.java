package com.oficina.service;

import com.oficina.dto.MetricasTempoResponse;
import com.oficina.repository.OrdemServicoRepository;
import com.oficina.repository.ServicoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MetricasService {

    private final OrdemServicoRepository ordemServicoRepository;
    private final ServicoRepository servicoRepository;

    @Transactional(readOnly = true)
    public MetricasTempoResponse tempoMedioExecucao() {
        Double estimado = servicoRepository.mediaTempoEstimadoMinutosAtivos();
        Double real = ordemServicoRepository.mediaTempoExecucaoMinutosReal();
        long amostras = ordemServicoRepository.countOrdensComTempoMedido();
        return new MetricasTempoResponse(
                estimado != null ? estimado : 0.0,
                real != null ? real : 0.0,
                amostras
        );
    }
}
