package com.oficina.saga.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Append-only audit trail of one saga: states, commands, replies, failures, compensations. */
@Entity
@Table(name = "os_saga_passo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OsSagaPasso {
    @Id private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "saga_id", nullable = false, updatable = false)
    private OsSaga saga;

    @Column(nullable = false, updatable = false) private int ordem;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 30) private EtapaSaga etapa;
    @Column(nullable = false, updatable = false, length = 100) private String evento;
    @Enumerated(EnumType.STRING) @Column(name = "estado_saga", nullable = false, updatable = false, length = 40)
    private EstadoSaga estadoSaga;
    @Column(name = "ocorrido_em", nullable = false, updatable = false) private Instant ocorridoEm;

    OsSagaPasso(OsSaga saga, int ordem, EtapaSaga etapa, String evento, EstadoSaga estadoSaga, Instant ocorridoEm) {
        this.id = UUID.randomUUID();
        this.saga = saga;
        this.ordem = ordem;
        this.etapa = etapa;
        this.evento = evento;
        this.estadoSaga = estadoSaga;
        this.ocorridoEm = ocorridoEm;
    }
}
