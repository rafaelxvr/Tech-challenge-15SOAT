CREATE TABLE os_saga (
    id UUID PRIMARY KEY,
    os_id UUID NOT NULL UNIQUE REFERENCES ordens_servico(id),
    estado TEXT NOT NULL CHECK (estado IN ('AGUARDANDO_DIAGNOSTICO','AGUARDANDO_ORCAMENTO','AGUARDANDO_DECISAO',
        'AGUARDANDO_PAGAMENTO','RESERVANDO_PECAS','AGUARDANDO_REPARO','EM_EXECUCAO','CONCLUIDA','COMPENSANDO',
        'CANCELADA','FALHA_COMPENSACAO')),
    etapa TEXT NOT NULL CHECK (etapa IN ('DIAGNOSTICO','ORCAMENTO','DECISAO','PAGAMENTO','RESERVA_PECAS','REPARO','EXECUCAO')),
    versao_orcamento INT NOT NULL CHECK (versao_orcamento > 0),
    comando_aguardado_id UUID,
    comando_aguardado TEXT,
    prazo TIMESTAMPTZ,
    orcamento_id UUID,
    valor_orcado NUMERIC(10,2) CHECK (valor_orcado >= 0),
    checkout_url TEXT CHECK (checkout_url IS NULL OR checkout_url ~ '^https://'),
    decisao_aprovada BOOLEAN,
    motivo_falha TEXT,
    compensacoes TEXT NOT NULL DEFAULT '',
    tentativas_compensacao INT NOT NULL DEFAULT 0 CHECK (tentativas_compensacao >= 0),
    criado_em TIMESTAMPTZ NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL,
    versao BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_os_saga_prazo ON os_saga(prazo) WHERE prazo IS NOT NULL;

CREATE TABLE os_saga_passo (
    id UUID PRIMARY KEY,
    saga_id UUID NOT NULL REFERENCES os_saga(id),
    ordem INT NOT NULL CHECK (ordem > 0),
    etapa TEXT NOT NULL,
    evento TEXT NOT NULL CHECK (evento ~ '^[A-Z]+:[A-Za-z_]{1,64}$'),
    estado_saga TEXT NOT NULL,
    ocorrido_em TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_saga_passo_ordem UNIQUE (saga_id, ordem)
);

-- Commands to other services. Identity column: no separate sequence grant needed.
CREATE TABLE saga_outbox (
    message_id UUID PRIMARY KEY,
    sequencia BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    os_id UUID NOT NULL REFERENCES ordens_servico(id),
    destino TEXT NOT NULL CHECK (destino IN ('BILLING','EXECUCAO')),
    tipo TEXT NOT NULL CHECK (tipo ~ '^[A-Z][A-Za-z]{2,63}$'),
    envelope JSONB NOT NULL CHECK (jsonb_typeof(envelope) = 'object' AND octet_length(envelope::text) <= 65536),
    estado TEXT NOT NULL DEFAULT 'PENDING' CHECK (estado IN ('PENDING','PUBLISHED','BLOCKED')),
    tentativas INT NOT NULL DEFAULT 0 CHECK (tentativas >= 0),
    disponivel_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    publicado_em TIMESTAMPTZ,
    queue_message_id TEXT,
    ultimo_erro_codigo TEXT CHECK (ultimo_erro_codigo ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

CREATE INDEX idx_saga_outbox_pendente ON saga_outbox(disponivel_em, sequencia) WHERE estado = 'PENDING';
CREATE INDEX idx_saga_outbox_os ON saga_outbox(os_id, sequencia);

CREATE TABLE saga_inbox (
    message_id UUID PRIMARY KEY,
    tipo TEXT NOT NULL CHECK (tipo ~ '^[A-Z][A-Za-z]{2,63}$'),
    recebido_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
