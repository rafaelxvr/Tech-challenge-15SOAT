package com.oficina.service;

import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.config.CorrelationFilter;
import com.oficina.entity.OrdemServico;
import com.oficina.entity.OsHistorico;
import com.oficina.repository.OrdemServicoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/** Shared by staff commands and the saga: canonical history time plus the status notification. */
@Component
public class RegistroTransicaoOs {
    private final OrdemServicoRepository ordens;
    private final NotificacaoPort notificacaoPort;
    private final Clock clock;
    private final ZoneId zonaCompatibilidade;

    public RegistroTransicaoOs(OrdemServicoRepository ordens, NotificacaoPort notificacaoPort, Clock clock,
                               @Value("${oficina.historico.zona-compatibilidade}") String zonaCompatibilidade) {
        this.ordens = ordens;
        this.notificacaoPort = notificacaoPort;
        this.clock = clock;
        this.zonaCompatibilidade = ZoneId.of(zonaCompatibilidade);
    }

    public Instant preparar(OrdemServico os) {
        os.setZonaCompatibilidade(zonaCompatibilidade);
        return clock.instant();
    }

    public void notificar(OrdemServico os) {
        // Persist generated order number, optimistic versions and canonical history before JDBC insertion.
        ordens.flush();
        OsHistorico historico = os.getHistorico().stream()
                .filter(h -> h.getSequencia() != null && h.getSequencia() == os.getSequenciaHistorico())
                .findFirst().orElseThrow(() -> new IllegalStateException("Canonical history required"));
        notificacaoPort.notificarAtualizacaoStatus(new StatusOrdemServicoRegistrado(
                UUID.randomUUID(), StatusOrdemServicoRegistrado.EVENT_TYPE,
                StatusOrdemServicoRegistrado.SCHEMA_VERSION, os.getId(), os.getNumero(),
                os.getCliente().getId(), os.getCliente().getVersaoIdentidade(), historico.getSequencia(),
                historico.getStatusAnterior() == null ? null : historico.getStatusAnterior().name(),
                historico.getStatusNovo().name(), historico.getOcorridoEm(),
                CorrelationFilter.correlationId(), CorrelationFilter.traceparent()));
    }
}
