package com.oficina.saga.application;

import com.oficina.application.port.out.CicloOsPort;
import com.oficina.exception.BusinessRuleException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.saga.adapter.out.persistence.OsSagaRepository;
import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CicloOsSagaService implements CicloOsPort {
    private static final Logger LOG = LoggerFactory.getLogger(CicloOsSagaService.class);
    private final OsSagaRepository sagas;
    private final ExecutorEfeitos efeitos;
    private final RoteadorRespostas roteador;
    private final PrazosSaga prazos;
    private final Clock clock;
    private final TransactionTemplate transacaoCurta;
    private final TransactionTemplate transacaoPorSaga;

    public CicloOsSagaService(OsSagaRepository sagas, ExecutorEfeitos efeitos, RoteadorRespostas roteador,
                              PrazosSaga prazos, Clock clock, PlatformTransactionManager transacoes) {
        this.sagas = sagas;
        this.efeitos = efeitos;
        this.roteador = roteador;
        this.prazos = prazos;
        this.clock = clock;
        this.transacaoCurta = new TransactionTemplate(transacoes);
        this.transacaoPorSaga = new TransactionTemplate(transacoes);
        this.transacaoPorSaga.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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
        // The order stays AGUARDANDO_APROVACAO while an expired decision is being compensated.
        if (saga.getEstado() != EstadoSaga.AGUARDANDO_DECISAO) {
            throw new BusinessRuleException("Orçamento não está mais aguardando decisão.");
        }
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

    /** Each overdue saga expires in its own transaction, so one failure never blocks the others. */
    public int expirarPrazosVencidos(int limite) {
        List<UUID> vencidas = transacaoCurta.execute(st -> sagas
                .findVencidasParaProcessar(clock.instant(), PageRequest.of(0, limite)).stream()
                .map(OsSaga::getId).toList());
        int processadas = 0;
        for (UUID id : vencidas) {
            try {
                if (Boolean.TRUE.equals(transacaoPorSaga.execute(st -> expirar(id)))) processadas++;
            } catch (RuntimeException e) {
                registrarFalhaPrazo(e);
            }
        }
        return processadas;
    }

    private boolean expirar(UUID id) {
        Optional<OsSaga> encontrada = sagas.findById(id);
        if (encontrada.isEmpty()) return false;
        OsSaga saga = encontrada.get();
        saga.prazoExpirado(momento());
        efeitos.executar(saga);
        return true;
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

    private static void registrarFalhaPrazo(RuntimeException e) {
        MDC.put("event_name", "saga_prazo_falhou");
        MDC.put("error_code", e.getClass().getSimpleName());
        try { LOG.warn(""); } finally { MDC.remove("event_name"); MDC.remove("error_code"); }
    }
}
