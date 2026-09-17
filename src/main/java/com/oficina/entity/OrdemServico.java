package com.oficina.entity;

import com.oficina.exception.BusinessRuleException;
import com.oficina.domain.identidade.Ator;
import com.oficina.domain.identidade.TipoAtor;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ordens_servico")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrdemServico {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    @Setter(AccessLevel.NONE)
    @Column(name = "versao", nullable = false)
    private long versao;

    @Setter(AccessLevel.NONE)
    @Column(name = "sequencia_historico", nullable = false)
    private long sequenciaHistorico;

    @Setter(AccessLevel.NONE)
    @Column(name = "criado_em_utc", updatable = false)
    private Instant criadoEmUtc;

    @Setter(AccessLevel.NONE)
    @Builder.Default
    @Column(name = "historico_completo_desde_inicio", nullable = false)
    private boolean historicoCompletoDesdeInicio = true;

    /** Explicit compatibility zone supplied by the application or fixture provenance. */
    @Transient
    private ZoneId zonaCompatibilidade;

    @Generated(event = EventType.INSERT)
    @Column(name = "numero", nullable = false, unique = true, insertable = false, updatable = false)
    private Long numero;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cliente_id", nullable = false)
    private Cliente cliente;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "veiculo_id", nullable = false)
    private Veiculo veiculo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "status_os")
    private StatusOrdemServico status;

    @Column(columnDefinition = "TEXT")
    private String descricao;

    @Column(columnDefinition = "TEXT")
    private String observacoes;

    @Column(name = "valor_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal valorTotal;

    @Column(name = "aprovado_em")
    private LocalDateTime aprovadoEm;

    @Column(name = "iniciado_em")
    private LocalDateTime iniciadoEm;

    @Column(name = "finalizado_em")
    private LocalDateTime finalizadoEm;

    @Column(name = "entregue_em")
    private LocalDateTime entregueEm;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private LocalDateTime atualizadoEm;

    @OneToMany(mappedBy = "ordemServico", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OsServicoItem> servicos = new ArrayList<>();

    @OneToMany(mappedBy = "ordemServico", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OsPecaItem> pecas = new ArrayList<>();

    @OneToMany(mappedBy = "ordemServico", cascade = CascadeType.ALL, orphanRemoval = true)
    @org.hibernate.annotations.SQLOrder("sequencia ASC NULLS FIRST, criado_em ASC")
    @Builder.Default
    private List<OsHistorico> historico = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        Objects.requireNonNull(criadoEm, "Registre o histórico inicial antes de persistir a ordem");
        if (valorTotal == null) {
            valorTotal = BigDecimal.ZERO;
        }
    }

    public void adicionarServico(OsServicoItem item) {
        item.setOrdemServico(this);
        servicos.add(item);
    }

    public void adicionarPeca(OsPecaItem item) {
        item.setOrdemServico(this);
        pecas.add(item);
    }

    public void recalcularValorTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (OsServicoItem s : servicos) {
            total = total.add(s.getValorTotal());
        }
        for (OsPecaItem p : pecas) {
            total = total.add(p.getValorTotal());
        }
        valorTotal = total;
    }

    private void registrarHistorico(StatusOrdemServico anterior, StatusOrdemServico novo,
                                    Ator ator, Instant ocorridoEm, String observacao) {
        Objects.requireNonNull(ator, "Ator obrigatório");
        Objects.requireNonNull(ocorridoEm, "Instante obrigatório");
        LocalDateTime compatibilidade = LocalDateTime.ofInstant(ocorridoEm,
                Objects.requireNonNull(zonaCompatibilidade, "Zona de compatibilidade obrigatória"));
        long proximaSequencia = Math.incrementExact(sequenciaHistorico);
        OsHistorico evento = OsHistorico.builder()
                .ordemServico(this).statusAnterior(anterior).statusNovo(novo)
                .observacao(observacao).atorTipo(ator.tipo())
                .alteradoPor(ator.tipo() == TipoAtor.STAFF ? ator.id() : null)
                .atorClienteId(ator.tipo() == TipoAtor.CUSTOMER ? ator.id() : null)
                .ocorridoEm(ocorridoEm).sequencia(proximaSequencia).criadoEm(compatibilidade)
                .build();
        historico.add(evento);
        sequenciaHistorico = proximaSequencia;
        atualizadoEm = compatibilidade;
    }

    public void registrarHistoricoInicial(Ator ator, Instant ocorridoEm, String observacao) {
        if (status != StatusOrdemServico.RECEBIDA || !historico.isEmpty() || sequenciaHistorico != 0) {
            throw new BusinessRuleException("Histórico inicial já registrado ou ordem fora do estado RECEBIDA.");
        }
        registrarHistorico(null, StatusOrdemServico.RECEBIDA, ator, ocorridoEm, observacao);
        criadoEmUtc = ocorridoEm;
        criadoEm = atualizadoEm;
    }

    public void iniciarDiagnostico(Ator ator, Instant ocorridoEm, String observacao) {
        assertTransicao(StatusOrdemServico.EM_DIAGNOSTICO, EnumSet.of(StatusOrdemServico.RECEBIDA));
        aplicarNovoStatus(StatusOrdemServico.EM_DIAGNOSTICO, ator, ocorridoEm, observacao);
    }

    public void enviarOrcamentoParaAprovacao(Ator ator, Instant ocorridoEm, String observacao) {
        assertTransicao(StatusOrdemServico.AGUARDANDO_APROVACAO, EnumSet.of(StatusOrdemServico.EM_DIAGNOSTICO));
        aplicarNovoStatus(StatusOrdemServico.AGUARDANDO_APROVACAO, ator, ocorridoEm, observacao);
    }

    public void aprovarExecucaoCliente(Ator ator, Instant ocorridoEm, String observacao) {
        assertTransicao(StatusOrdemServico.EM_EXECUCAO, EnumSet.of(StatusOrdemServico.AGUARDANDO_APROVACAO));
        aplicarNovoStatus(StatusOrdemServico.EM_EXECUCAO, ator, ocorridoEm, observacao);
        aprovadoEm = atualizadoEm;
        iniciadoEm = atualizadoEm;
    }

    /** Recusa do orçamento: volta para diagnóstico para revisão (exclusão lógica da fila de aprovação). */
    public void recusarOrcamentoCliente(Ator ator, Instant ocorridoEm, String observacao) {
        assertTransicao(StatusOrdemServico.EM_DIAGNOSTICO, EnumSet.of(StatusOrdemServico.AGUARDANDO_APROVACAO));
        aplicarNovoStatus(StatusOrdemServico.EM_DIAGNOSTICO, ator, ocorridoEm,
                observacao != null ? observacao : "Orçamento recusado pelo cliente");
    }

    public void finalizarServico(Ator ator, Instant ocorridoEm, String observacao) {
        assertTransicao(StatusOrdemServico.FINALIZADA, EnumSet.of(StatusOrdemServico.EM_EXECUCAO));
        aplicarNovoStatus(StatusOrdemServico.FINALIZADA, ator, ocorridoEm, observacao);
        finalizadoEm = atualizadoEm;
    }

    public void registrarEntrega(Ator ator, Instant ocorridoEm, String observacao) {
        assertTransicao(StatusOrdemServico.ENTREGUE, EnumSet.of(StatusOrdemServico.FINALIZADA));
        aplicarNovoStatus(StatusOrdemServico.ENTREGUE, ator, ocorridoEm, observacao);
        entregueEm = atualizadoEm;
    }

    private void assertTransicao(StatusOrdemServico destino, EnumSet<StatusOrdemServico> permitidos) {
        if (!permitidos.contains(status)) {
            throw new BusinessRuleException(
                    "Transição inválida de '%s' para '%s'.".formatted(status, destino)
            );
        }
    }

    private void aplicarNovoStatus(StatusOrdemServico novo, Ator ator, Instant ocorridoEm, String observacao) {
        StatusOrdemServico anterior = status;
        registrarHistorico(anterior, novo, ator, ocorridoEm, observacao);
        status = novo;
    }
}
