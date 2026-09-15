package com.oficina.entity;

import com.oficina.domain.identidade.Ator;
import com.oficina.domain.identidade.TipoAtor;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "os_historico")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OsHistorico {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "os_id", nullable = false)
    private OrdemServico ordemServico;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status_anterior", columnDefinition = "status_os")
    private StatusOrdemServico statusAnterior;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status_novo", nullable = false, columnDefinition = "status_os")
    private StatusOrdemServico statusNovo;

    @Column(columnDefinition = "TEXT")
    private String observacao;

    @Column(name = "alterado_por")
    private UUID alteradoPor;

    @Enumerated(EnumType.STRING)
    @Column(name = "ator_tipo", nullable = false, length = 20)
    private TipoAtor atorTipo;

    @Column(name = "ator_cliente_id")
    private UUID atorClienteId;

    @Column(name = "ocorrido_em")
    private Instant ocorridoEm;

    @Column(name = "sequencia")
    private Long sequencia;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime criadoEm;

    @PrePersist
    protected void onCreate() {
        new Ator(atorTipo, atorTipo == TipoAtor.STAFF ? alteradoPor : atorClienteId);
        if ((atorTipo != TipoAtor.STAFF && alteradoPor != null)
                || (atorTipo != TipoAtor.CUSTOMER && atorClienteId != null)) {
            throw new IllegalArgumentException("Referências de ator incompatíveis");
        }
        Objects.requireNonNull(ocorridoEm, "Instante obrigatório");
        Objects.requireNonNull(criadoEm, "Horário de compatibilidade obrigatório");
        if (sequencia == null || sequencia <= 0) {
            throw new IllegalArgumentException("Sequência positiva obrigatória");
        }
    }
}
