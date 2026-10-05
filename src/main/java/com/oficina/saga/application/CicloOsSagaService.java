package com.oficina.saga.application;

import com.oficina.application.port.out.CicloOsPort;
import com.oficina.exception.BusinessRuleException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.saga.adapter.out.persistence.OsSagaRepository;
import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.domain.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CicloOsSagaService implements CicloOsPort {
    private static final Logger LOG = LoggerFactory.getLogger(CicloOsSagaService.class);
    private final OsSagaRepository sagas;
    private final ExecutorEfeitos efeitos;
    private final RoteadorRespostas roteador;
    private final PrazosSaga prazos;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void iniciar(UUID osId) {
        if (sagas.existsByOsId(osId)) throw new BusinessRuleException("Saga já iniciada para a ordem de serviço.");
        OsSaga saga = OsSaga.iniciar(osId, momento());
        efeitos.executar(saga);
        sagas.save(saga);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean conduzidaPorSaga(UUID osId) { return sagas.existsByOsId(osId); }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarDecisao(UUID osId, boolean aprovado) {
        OsSaga saga = sagas.findByOsId(osId)
                .orElseThrow(() -> new EntityNotFoundException("Saga da ordem de serviço", osId));
        saga.registrarDecisao(aprovado, momento());
        efeitos.executar(saga);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResumoSaga> resumo(UUID osId) {
        return sagas.findByOsId(osId).map(s -> new ResumoSaga(s.getEstado().name(), s.getCheckoutUrl()));
    }

    /** Called with the inbox row already inserted in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void aplicarResposta(Envelope envelope) {
        Optional<Resposta> resposta = Resposta.deTipo(envelope.type());
        if (resposta.isEmpty()) { registrarDescarte("saga_resposta_desconhecida"); return; }
        Optional<OsSaga> encontrada = sagas.findById(envelope.sagaId());
        if (encontrada.isEmpty() || !encontrada.get().getOsId().equals(envelope.osId())) {
            registrarDescarte("saga_resposta_sem_saga"); return;
        }
        OsSaga saga = encontrada.get();
        if (!saga.aceita(envelope.causationId())) { registrarDescarte("saga_resposta_obsoleta"); return; }
        roteador.aplicar(saga, resposta.get(), envelope.payload(), momento());
        efeitos.executar(saga);
    }

    @Transactional
    public int expirarPrazosVencidos(int limite) {
        MomentoSaga m = momento();
        List<OsSaga> vencidas = sagas.findVencidasParaProcessar(m.agora(), PageRequest.of(0, limite));
        for (OsSaga saga : vencidas) {
            saga.prazoExpirado(m);
            efeitos.executar(saga);
        }
        return vencidas.size();
    }

    @Transactional(readOnly = true)
    public Optional<SagaDetalhe> detalhar(UUID osId) {
        return sagas.findByOsId(osId).map(s -> new SagaDetalhe(s.getId(), s.getOsId(), s.getEstado().name(),
                s.getEtapa().name(), s.getVersaoOrcamento(), s.getPrazo(),
                s.getMotivoFalha() == null ? null : s.getMotivoFalha().name(),
                s.compensacoesPendentes().stream().map(Enum::name).toList(),
                s.getPassos().stream().map(p -> new SagaDetalhe.Passo(p.getOrdem(), p.getEtapa().name(),
                        p.getEvento(), p.getEstadoSaga().name(), p.getOcorridoEm())).toList()));
    }

    private MomentoSaga momento() { return new MomentoSaga(clock.instant(), prazos); }

    private static void registrarDescarte(String evento) {
        MDC.put("event_name", evento);
        try { LOG.info(""); } finally { MDC.remove("event_name"); }
    }
}
