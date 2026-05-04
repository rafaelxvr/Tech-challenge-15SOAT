package com.oficina.service;

import com.oficina.dto.MetricasTempoResponse;
import com.oficina.repository.OrdemServicoRepository;
import com.oficina.repository.ServicoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricasServiceTest {

    @Mock
    private OrdemServicoRepository ordemServicoRepository;

    @Mock
    private ServicoRepository servicoRepository;

    @InjectMocks
    private MetricasService metricasService;

    @Test
    void tempoMedioExecucao_agregaFontes() {
        when(servicoRepository.mediaTempoEstimadoMinutosAtivos()).thenReturn(90.0);
        when(ordemServicoRepository.mediaTempoExecucaoMinutosReal()).thenReturn(120.5);
        when(ordemServicoRepository.countOrdensComTempoMedido()).thenReturn(4L);

        MetricasTempoResponse r = metricasService.tempoMedioExecucao();

        assertThat(r.tempoMedioEstimadoServicosMinutos()).isEqualTo(90.0);
        assertThat(r.tempoMedioExecucaoRealMinutos()).isEqualTo(120.5);
        assertThat(r.ordensComTempoMedido()).isEqualTo(4L);
    }
}
