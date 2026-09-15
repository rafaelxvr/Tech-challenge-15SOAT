-- A full unique constraint supports the FK; V6's partial index remains untouched.
ALTER TABLE os_historico ADD CONSTRAINT uk_historico_referencia UNIQUE (os_id, sequencia);

CREATE TABLE outbox_eventos (
    event_id UUID PRIMARY KEY,
    os_id UUID NOT NULL REFERENCES ordens_servico(id),
    sequencia BIGINT NOT NULL CHECK (sequencia > 0),
    event_type TEXT NOT NULL CHECK (event_type = 'StatusOrdemServicoRegistrado'),
    schema_version INT NOT NULL CHECK (schema_version = 1),
    payload JSONB NOT NULL,
    estado TEXT NOT NULL DEFAULT 'PENDING' CHECK (estado IN ('PENDING', 'PUBLISHED', 'BLOCKED', 'SKIPPED')),
    tentativas INT NOT NULL DEFAULT 0 CHECK (tentativas >= 0),
    disponivel_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    publicado_em TIMESTAMPTZ,
    queue_message_id TEXT,
    ultimo_erro_codigo TEXT CHECK (ultimo_erro_codigo ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT uk_outbox_transicao UNIQUE (os_id, sequencia, event_type),
    CONSTRAINT fk_outbox_historico FOREIGN KEY (os_id, sequencia) REFERENCES os_historico(os_id, sequencia),
    CONSTRAINT ck_outbox_payload CHECK (
        jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 8192
        AND payload ?& ARRAY['eventId','eventType','schemaVersion','ordemId','numero','clienteId',
            'versaoIdentidadeCliente','sequencia','statusAnterior','statusNovo','ocorridoEm','correlationId','traceparent']
        AND payload - ARRAY['eventId','eventType','schemaVersion','ordemId','numero','clienteId',
            'versaoIdentidadeCliente','sequencia','statusAnterior','statusNovo','ocorridoEm','correlationId','traceparent'] = '{}'::jsonb
        AND payload->'schemaVersion' = to_jsonb(schema_version)
        AND payload->'eventId' = to_jsonb(event_id::text)
        AND payload->'ordemId' = to_jsonb(os_id::text)
        AND payload->'sequencia' = to_jsonb(sequencia)
        AND payload->'eventType' = to_jsonb(event_type)
    )
);

CREATE INDEX idx_outbox_pendente_disponivel ON outbox_eventos(disponivel_em, criado_em, event_id)
    WHERE estado = 'PENDING';

-- Recovery fields are symbolic audit references/codes, never arbitrary contact/secret text.
CREATE TABLE outbox_recuperacoes (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES outbox_eventos(event_id),
    acao TEXT NOT NULL CHECK (acao IN ('RETRY', 'SKIP')),
    operador_ref TEXT NOT NULL CHECK (operador_ref ~ '^[A-Za-z0-9_-]{1,128}$'),
    ocorrido_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    motivo TEXT NOT NULL CHECK (motivo ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

-- Live snapshot: downstream rechecks active status and version before resolving contact.
-- Both CPF and CNPJ participate; no document/contact enters the event itself.
CREATE VIEW notificacao_destinatario_snapshot AS
SELECT os.id AS ordem_id, os.numero, c.id AS cliente_id, c.ativo, c.email, c.versao_identidade
FROM ordens_servico os JOIN clientes c ON c.id = os.cliente_id;
-- Deployment roles and least-privilege grants are owned by I3/I6.
