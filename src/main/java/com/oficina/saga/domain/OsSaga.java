package com.oficina.saga.domain;

import com.oficina.exception.BusinessRuleException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrator of one service order. Decides every transition and records effects;
 * the application layer applies {@link #retirarAcoes()} and publishes {@link #retirarComandos()}
 * in the same transaction that saves this aggregate.
 */
@Entity
@Table(name = "os_saga")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OsSaga {
    @Id private UUID id;
    @Column(name = "os_id", nullable = false, unique = true, updatable = false) private UUID osId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private EstadoSaga estado;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private EtapaSaga etapa;
    @Column(name = "versao_orcamento", nullable = false) private int versaoOrcamento;
    @Column(name = "comando_aguardado_id") private UUID comandoAguardadoId;
    @Enumerated(EnumType.STRING) @Column(name = "comando_aguardado", length = 40) private Comando comandoAguardado;
    @Column(name = "prazo") private Instant prazo;
    @Column(name = "orcamento_id") private UUID orcamentoId;
    @Column(name = "valor_orcado", precision = 10, scale = 2) private BigDecimal valorOrcado;
    @Column(name = "checkout_url", length = 2048) private String checkoutUrl;
    @Column(name = "decisao_aprovada") private Boolean decisaoAprovada;
    @Enumerated(EnumType.STRING) @Column(name = "motivo_falha", length = 40) private MotivoFalha motivoFalha;
    @Getter(AccessLevel.NONE) @Column(name = "compensacoes", nullable = false) private String compensacoes = "";
    @Column(name = "tentativas_compensacao", nullable = false) private int tentativasCompensacao;
    @Column(name = "criado_em", nullable = false, updatable = false) private Instant criadoEm;
    @Column(name = "atualizado_em", nullable = false) private Instant atualizadoEm;
    @Version @Column(name = "versao", nullable = false) private Long versao;

    @OneToMany(mappedBy = "saga", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordem ASC")
    private List<OsSagaPasso> passos = new ArrayList<>();

    @Transient @Getter(AccessLevel.NONE) private final List<ComandoSaga> comandos = new ArrayList<>();
    @Transient @Getter(AccessLevel.NONE) private final List<AcaoLocal> acoes = new ArrayList<>();

    public static OsSaga iniciar(UUID osId, MomentoSaga m) {
        OsSaga saga = new OsSaga();
        saga.id = UUID.randomUUID();
        saga.osId = Objects.requireNonNull(osId, "osId");
        saga.versaoOrcamento = 1;
        saga.criadoEm = m.agora();
        saga.atualizadoEm = m.agora();
        saga.acoes.add(AcaoLocal.INICIAR_DIAGNOSTICO_OS);
        saga.solicitarDiagnostico(m);
        return saga;
    }

    /** A reply is accepted only when it answers the command this saga is waiting for. */
    public boolean aceita(UUID causationId) {
        return causationId != null && causationId.equals(comandoAguardadoId);
    }

    public void registrarResposta(Resposta resposta, MomentoSaga m) {
        registrar("RESPOSTA:" + resposta.tipo(), m);
    }

    public void diagnosticoConcluido(MomentoSaga m) {
        exigir(EstadoSaga.AGUARDANDO_DIAGNOSTICO);
        desempilhar(Compensacao.CANCELAR_EXECUCAO);
        // Pushed with the command, like the execution steps: a late GerarOrcamento after a timeout or failure
        // may still create an orçamento, so it is always cancelled (billing answers with a null id if none exists).
        empilhar(Compensacao.CANCELAR_ORCAMENTO);
        mudarPara(EstadoSaga.AGUARDANDO_ORCAMENTO, EtapaSaga.ORCAMENTO, m);
        emitir(Comando.GERAR_ORCAMENTO, m, m.prazos().respostaAutomatica());
    }

    public void orcamentoGerado(UUID orcamentoId, BigDecimal valor, MomentoSaga m) {
        exigir(EstadoSaga.AGUARDANDO_ORCAMENTO);
        if (orcamentoId == null || valor == null || valor.signum() < 0) {
            throw new IllegalArgumentException("Orçamento gerado exige id e valor não negativo");
        }
        this.orcamentoId = orcamentoId;
        this.valorOrcado = valor;
        acoes.add(AcaoLocal.ENVIAR_ORCAMENTO_OS);
        mudarPara(EstadoSaga.AGUARDANDO_DECISAO, EtapaSaga.DECISAO, m);
        aguardarSemComando(m.agora().plus(m.prazos().decisao()));
    }

    public void registrarDecisao(boolean aprovado, MomentoSaga m) {
        exigir(EstadoSaga.AGUARDANDO_DECISAO);
        if (comandoAguardadoId != null) {
            throw new BusinessRuleException("Decisão do orçamento já registrada; aguardando confirmação.");
        }
        decisaoAprovada = aprovado;
        registrar("DECISAO:" + (aprovado ? "APROVADO" : "RECUSADO"), m);
        emitir(Comando.REGISTRAR_DECISAO, m, m.prazos().respostaAutomatica());
    }

    /** Payment replies keep answering RegistrarDecisao, so the awaited command id is kept. */
    public void orcamentoAprovado(String checkoutUrl, MomentoSaga m) {
        if (checkoutUrl == null || !checkoutUrl.startsWith("https://")) {
            throw new IllegalArgumentException("checkoutUrl deve ser https");
        }
        exigir(EstadoSaga.AGUARDANDO_DECISAO);
        if (!Boolean.TRUE.equals(decisaoAprovada)) {
            throw new IllegalStateException("Aprovação recebida sem decisão de aprovação");
        }
        this.checkoutUrl = checkoutUrl;
        acoes.add(AcaoLocal.AGUARDAR_PAGAMENTO_OS);
        mudarPara(EstadoSaga.AGUARDANDO_PAGAMENTO, EtapaSaga.PAGAMENTO, m);
        prazo = m.agora().plus(m.prazos().pagamento());
    }

    public void orcamentoRecusado(MomentoSaga m) {
        exigir(EstadoSaga.AGUARDANDO_DECISAO);
        if (!Boolean.FALSE.equals(decisaoAprovada)) {
            throw new IllegalStateException("Recusa recebida sem decisão de recusa");
        }
        desempilhar(Compensacao.CANCELAR_ORCAMENTO);
        orcamentoId = null;
        valorOrcado = null;
        decisaoAprovada = null;
        checkoutUrl = null;
        versaoOrcamento++;
        acoes.add(AcaoLocal.VOLTAR_DIAGNOSTICO_OS);
        solicitarDiagnostico(m);
    }

    public void pagamentoConfirmado(MomentoSaga m) {
        exigir(EstadoSaga.AGUARDANDO_PAGAMENTO);
        empilhar(Compensacao.ESTORNAR_PAGAMENTO);
        mudarPara(EstadoSaga.RESERVANDO_PECAS, EtapaSaga.RESERVA_PECAS, m);
        prazo = null;
    }

    public void pecasReservadas(MomentoSaga m) {
        exigir(EstadoSaga.RESERVANDO_PECAS);
        empilhar(Compensacao.REPOR_ESTOQUE);
        empilhar(Compensacao.CANCELAR_EXECUCAO);
        mudarPara(EstadoSaga.AGUARDANDO_REPARO, EtapaSaga.REPARO, m);
        emitir(Comando.INICIAR_REPARO, m, m.prazos().respostaAutomatica());
    }

    /** Pivot: once the repair started nothing is compensated; ExecucaoFinalizada answers IniciarReparo. */
    public void reparoIniciado(MomentoSaga m) {
        exigir(EstadoSaga.AGUARDANDO_REPARO);
        gravarPilha(new ArrayList<>());
        acoes.add(AcaoLocal.INICIAR_EXECUCAO_OS);
        mudarPara(EstadoSaga.EM_EXECUCAO, EtapaSaga.EXECUCAO, m);
        prazo = null;
    }

    public void execucaoFinalizada(MomentoSaga m) {
        exigir(EstadoSaga.EM_EXECUCAO);
        acoes.add(AcaoLocal.FINALIZAR_OS);
        mudarPara(EstadoSaga.CONCLUIDA, EtapaSaga.EXECUCAO, m);
        encerrarEspera();
    }

    public void falhar(MotivoFalha motivo, MomentoSaga m) {
        if (estado.terminal() || estado == EstadoSaga.EM_EXECUCAO || estado == EstadoSaga.COMPENSANDO) {
            throw new IllegalStateException("Saga %s não admite falha em %s".formatted(id, estado));
        }
        motivoFalha = Objects.requireNonNull(motivo, "motivo");
        registrar("FALHA:" + motivo.name(), m);
        mudarPara(EstadoSaga.COMPENSANDO, etapa, m);
        tentativasCompensacao = 0;
        avancarCompensacao(m);
    }

    public void compensacaoConcluida(MomentoSaga m) {
        exigir(EstadoSaga.COMPENSANDO);
        List<Compensacao> pilha = pilha();
        pilha.remove(pilha.size() - 1);
        gravarPilha(pilha);
        tentativasCompensacao = 0;
        avancarCompensacao(m);
    }

    public void compensacaoFalhou(MomentoSaga m) {
        exigir(EstadoSaga.COMPENSANDO);
        mudarPara(EstadoSaga.FALHA_COMPENSACAO, etapa, m);
        encerrarEspera();
    }

    public void prazoExpirado(MomentoSaga m) {
        if (prazo == null || m.agora().isBefore(prazo)) return;
        switch (estado) {
            case COMPENSANDO -> reenviarCompensacao(m);
            case AGUARDANDO_DIAGNOSTICO -> falhar(MotivoFalha.DIAGNOSTICO_SEM_RESPOSTA, m);
            case AGUARDANDO_ORCAMENTO -> falhar(MotivoFalha.ORCAMENTO_SEM_RESPOSTA, m);
            case AGUARDANDO_DECISAO -> falhar(comandoAguardadoId == null
                    ? MotivoFalha.DECISAO_EXPIRADA : MotivoFalha.DECISAO_SEM_RESPOSTA, m);
            case AGUARDANDO_PAGAMENTO -> falhar(MotivoFalha.PAGAMENTO_EXPIRADO, m);
            case AGUARDANDO_REPARO -> falhar(MotivoFalha.REPARO_SEM_RESPOSTA, m);
            default -> throw new IllegalStateException("Estado %s não tem prazo".formatted(estado));
        }
    }

    public List<ComandoSaga> retirarComandos() {
        List<ComandoSaga> copia = List.copyOf(comandos);
        comandos.clear();
        return copia;
    }

    public List<AcaoLocal> retirarAcoes() {
        List<AcaoLocal> copia = List.copyOf(acoes);
        acoes.clear();
        return copia;
    }

    public List<Compensacao> compensacoesPendentes() {
        return List.copyOf(pilha());
    }

    private void solicitarDiagnostico(MomentoSaga m) {
        mudarPara(EstadoSaga.AGUARDANDO_DIAGNOSTICO, EtapaSaga.DIAGNOSTICO, m);
        empilhar(Compensacao.CANCELAR_EXECUCAO);
        emitir(Comando.INICIAR_DIAGNOSTICO, m, m.prazos().diagnostico());
    }

    private void exigir(EstadoSaga esperado) {
        if (estado != esperado) {
            throw new IllegalStateException("Saga %s em %s; esperado %s".formatted(id, estado, esperado));
        }
    }

    private void mudarPara(EstadoSaga novo, EtapaSaga novaEtapa, MomentoSaga m) {
        estado = novo;
        etapa = novaEtapa;
        atualizadoEm = m.agora();
        registrar("ESTADO:" + novo.name(), m);
    }

    private void emitir(Comando comando, MomentoSaga m, Duration espera) {
        UUID messageId = UUID.randomUUID();
        comandos.add(new ComandoSaga(messageId, comando, etapa));
        comandoAguardadoId = messageId;
        comandoAguardado = comando;
        prazo = m.agora().plus(espera);
        registrar("COMANDO:" + comando.tipo(), m);
    }

    private void aguardarSemComando(Instant novoPrazo) {
        comandoAguardadoId = null;
        comandoAguardado = null;
        prazo = novoPrazo;
    }

    private void encerrarEspera() {
        comandoAguardadoId = null;
        comandoAguardado = null;
        prazo = null;
    }

    private void registrar(String evento, MomentoSaga m) {
        passos.add(new OsSagaPasso(this, passos.size() + 1, etapa, evento, estado, m.agora()));
    }

    private List<Compensacao> pilha() {
        if (compensacoes.isBlank()) return new ArrayList<>();
        return Arrays.stream(compensacoes.split(",")).map(Compensacao::valueOf)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void gravarPilha(List<Compensacao> pilha) {
        compensacoes = pilha.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private void empilhar(Compensacao compensacao) {
        List<Compensacao> pilha = pilha();
        pilha.add(compensacao);
        gravarPilha(pilha);
    }

    private void desempilhar(Compensacao esperada) {
        List<Compensacao> pilha = pilha();
        if (pilha.isEmpty() || pilha.get(pilha.size() - 1) != esperada) {
            throw new IllegalStateException("Topo da pilha de compensação difere de " + esperada);
        }
        pilha.remove(pilha.size() - 1);
        gravarPilha(pilha);
    }

    /** Runs local compensations inline and stops at the first remote one, which stays on top until confirmed. */
    private void avancarCompensacao(MomentoSaga m) {
        while (true) {
            List<Compensacao> pilha = pilha();
            if (pilha.isEmpty()) {
                acoes.add(AcaoLocal.CANCELAR_OS);
                mudarPara(EstadoSaga.CANCELADA, etapa, m);
                encerrarEspera();
                return;
            }
            Compensacao proxima = pilha.get(pilha.size() - 1);
            registrar("COMPENSACAO:" + proxima.name(), m);
            if (!proxima.local()) {
                emitir(proxima.comando(), m, m.prazos().compensacao());
                return;
            }
            pilha.remove(pilha.size() - 1);
            gravarPilha(pilha);
            acoes.add(AcaoLocal.REPOR_ESTOQUE);
        }
    }

    private void reenviarCompensacao(MomentoSaga m) {
        tentativasCompensacao++;
        if (tentativasCompensacao >= m.prazos().maxTentativasCompensacao()) {
            mudarPara(EstadoSaga.FALHA_COMPENSACAO, etapa, m);
            encerrarEspera();
            return;
        }
        List<Compensacao> pilha = pilha();
        Compensacao topo = pilha.get(pilha.size() - 1);
        registrar("REENVIO:" + topo.name(), m);
        emitir(topo.comando(), m, m.prazos().compensacao());
    }
}
