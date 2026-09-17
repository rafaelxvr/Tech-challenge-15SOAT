ALTER TABLE clientes ADD COLUMN versao BIGINT NOT NULL DEFAULT 0;
ALTER TABLE clientes ADD COLUMN versao_identidade BIGINT NOT NULL DEFAULT 1
    CHECK (versao_identidade > 0);

CREATE VIEW auth_cliente_snapshot AS
SELECT id, documento AS cpf, ativo, email, versao_identidade
FROM clientes WHERE tipo_documento = 'CPF';

CREATE TABLE cliente_identidade_auditoria (
    id UUID PRIMARY KEY,
    cliente_id UUID NOT NULL REFERENCES clientes(id),
    staff_id UUID NOT NULL REFERENCES usuarios(id),
    campos TEXT[] NOT NULL CHECK (
        cardinality(campos) > 0 AND
        campos <@ ARRAY['tipo_documento', 'documento', 'email', 'ativo']::TEXT[]
    ),
    versao_anterior BIGINT NOT NULL CHECK (versao_anterior > 0),
    versao_nova BIGINT NOT NULL CHECK (versao_nova = versao_anterior + 1),
    ocorrido_em TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_cliente_identidade_auditoria_cliente ON cliente_identidade_auditoria(cliente_id);
