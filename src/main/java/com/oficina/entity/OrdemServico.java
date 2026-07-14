package com.oficina.entity;

import com.oficina.exception.BusinessRuleException;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    @OrderBy("criadoEm ASC")
    @Builder.Default
    private List<OsHistorico> historico = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        criadoEm = LocalDateTime.now();
        atualizadoEm = LocalDateTime.now();
        if (valorTotal == null) {
            valorTotal = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        atualizadoEm = LocalDateTime.now();
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

    public void registrarHistorico(StatusOrdemServico anterior, StatusOrdemServico novo, UUID usuarioId, String observacao) {
        historico.add(OsHistorico.builder()
                .ordemServico(this)
                .statusAnterior(anterior)
                .statusNovo(novo)
                .observacao(observacao)
                .alteradoPor(usuarioId)
                .build());
    }

    public void registrarHistoricoInicial(UUID usuarioId, String observacao) {
        historico.add(OsHistorico.builder()
                .ordemServico(this)
                .statusAnterior(null)
                .statusNovo(StatusOrdemServico.RECEBIDA)
                .observacao(observacao)
                .alteradoPor(usuarioId)
                .build());
    }

    public void iniciarDiagnostico(UUID usuarioId, String observacao) {
        assertTransicao(StatusOrdemServico.EM_DIAGNOSTICO, EnumSet.of(StatusOrdemServico.RECEBIDA));
        aplicarNovoStatus(StatusOrdemServico.EM_DIAGNOSTICO, usuarioId, observacao);
    }

    public void enviarOrcamentoParaAprovacao(UUID usuarioId, String observacao) {
        assertTransicao(StatusOrdemServico.AGUARDANDO_APROVACAO, EnumSet.of(StatusOrdemServico.EM_DIAGNOSTICO));
        aplicarNovoStatus(StatusOrdemServico.AGUARDANDO_APROVACAO, usuarioId, observacao);
    }

    public void aprovarExecucaoCliente(UUID usuarioId, String observacao) {
        assertTransicao(StatusOrdemServico.EM_EXECUCAO, EnumSet.of(StatusOrdemServico.AGUARDANDO_APROVACAO));
        aplicarNovoStatus(StatusOrdemServico.EM_EXECUCAO, usuarioId, observacao);
        aprovadoEm = LocalDateTime.now();
        iniciadoEm = LocalDateTime.now();
    }

    /** Recusa do orçamento: volta para diagnóstico para revisão (exclusão lógica da fila de aprovação). */
    public void recusarOrcamentoCliente(UUID usuarioId, String observacao) {
        assertTransicao(StatusOrdemServico.EM_DIAGNOSTICO, EnumSet.of(StatusOrdemServico.AGUARDANDO_APROVACAO));
        aplicarNovoStatus(StatusOrdemServico.EM_DIAGNOSTICO, usuarioId,
                observacao != null ? observacao : "Orçamento recusado pelo cliente");
    }

    public void finalizarServico(UUID usuarioId, String observacao) {
        assertTransicao(StatusOrdemServico.FINALIZADA, EnumSet.of(StatusOrdemServico.EM_EXECUCAO));
        aplicarNovoStatus(StatusOrdemServico.FINALIZADA, usuarioId, observacao);
        finalizadoEm = LocalDateTime.now();
    }

    public void registrarEntrega(UUID usuarioId, String observacao) {
        assertTransicao(StatusOrdemServico.ENTREGUE, EnumSet.of(StatusOrdemServico.FINALIZADA));
        aplicarNovoStatus(StatusOrdemServico.ENTREGUE, usuarioId, observacao);
        entregueEm = LocalDateTime.now();
    }

    private void assertTransicao(StatusOrdemServico destino, EnumSet<StatusOrdemServico> permitidos) {
        if (!permitidos.contains(status)) {
            throw new BusinessRuleException(
                    "Transição inválida de '%s' para '%s'.".formatted(status, destino)
            );
        }
    }

    private void aplicarNovoStatus(StatusOrdemServico novo, UUID usuarioId, String observacao) {
        StatusOrdemServico anterior = status;
        status = novo;
        registrarHistorico(anterior, novo, usuarioId, observacao);
    }
}
