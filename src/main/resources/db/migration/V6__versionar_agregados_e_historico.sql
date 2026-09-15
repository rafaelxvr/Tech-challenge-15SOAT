-- Preserve legacy timestamps: their timezone and chronology are not established.
ALTER TABLE ordens_servico
    ADD COLUMN versao BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN sequencia_historico BIGINT NOT NULL DEFAULT 0 CHECK (sequencia_historico >= 0),
    ADD COLUMN criado_em_utc TIMESTAMPTZ,
    ADD COLUMN historico_completo_desde_inicio BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE pecas ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;

ALTER TABLE os_historico
    ADD COLUMN ator_tipo VARCHAR(20),
    ADD COLUMN ator_cliente_id UUID REFERENCES clientes(id),
    ADD COLUMN ocorrido_em TIMESTAMPTZ,
    ADD COLUMN sequencia BIGINT;

UPDATE os_historico
SET ator_tipo = CASE WHEN alterado_por IS NOT NULL THEN 'STAFF' ELSE 'LEGACY_UNKNOWN' END;

ALTER TABLE os_historico
    ALTER COLUMN ator_tipo SET NOT NULL,
    ADD CONSTRAINT ck_os_historico_ator CHECK (
        (ator_tipo = 'STAFF' AND alterado_por IS NOT NULL AND ator_cliente_id IS NULL)
        OR (ator_tipo = 'CUSTOMER' AND alterado_por IS NULL AND ator_cliente_id IS NOT NULL)
        OR (ator_tipo IN ('SYSTEM', 'LEGACY_UNKNOWN') AND alterado_por IS NULL AND ator_cliente_id IS NULL)
    ),
    ADD CONSTRAINT ck_os_historico_canonico CHECK (
        (sequencia IS NULL AND ocorrido_em IS NULL)
        OR (sequencia IS NOT NULL AND sequencia > 0 AND ocorrido_em IS NOT NULL)
    );

CREATE UNIQUE INDEX uk_os_historico_os_sequencia
    ON os_historico (os_id, sequencia) WHERE sequencia IS NOT NULL;

-- No defaults for live history: old writers must be drained before applying V6.
-- Existing incomplete aggregates retain counter 0; their first new event is 1,
-- without assigning fictitious sequence numbers to their old rows.
-- The application owns update timestamps from the same injected event instant.
DROP TRIGGER update_os_updated_at ON ordens_servico;
