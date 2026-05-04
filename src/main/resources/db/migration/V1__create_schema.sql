-- ============================================================
-- V1__create_schema.sql
-- Criação do schema inicial do sistema de oficina mecânica
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ========================
-- ENUM TYPES
-- ========================
CREATE TYPE tipo_documento AS ENUM ('CPF', 'CNPJ');
CREATE TYPE status_os AS ENUM (
    'RECEBIDA',
    'EM_DIAGNOSTICO',
    'AGUARDANDO_APROVACAO',
    'EM_EXECUCAO',
    'FINALIZADA',
    'ENTREGUE'
);
CREATE TYPE role_usuario AS ENUM ('ADMIN', 'MECANICO', 'CLIENTE');

-- ========================
-- TABELA: usuarios
-- ========================
CREATE TABLE usuarios (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    senha       VARCHAR(255) NOT NULL,
    role        role_usuario NOT NULL DEFAULT 'CLIENTE',
    ativo       BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em   TIMESTAMP NOT NULL DEFAULT NOW(),
    atualizado_em TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ========================
-- TABELA: clientes
-- ========================
CREATE TABLE clientes (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nome            VARCHAR(255) NOT NULL,
    tipo_documento  tipo_documento NOT NULL,
    documento       VARCHAR(18) NOT NULL UNIQUE,
    email           VARCHAR(255) NOT NULL,
    telefone        VARCHAR(20) NOT NULL,
    cep             VARCHAR(9),
    logradouro      VARCHAR(255),
    numero          VARCHAR(20),
    complemento     VARCHAR(100),
    bairro          VARCHAR(100),
    cidade          VARCHAR(100),
    estado          CHAR(2),
    ativo           BOOLEAN NOT NULL DEFAULT TRUE,
    usuario_id      UUID REFERENCES usuarios(id),
    criado_em       TIMESTAMP NOT NULL DEFAULT NOW(),
    atualizado_em   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ========================
-- TABELA: veiculos
-- ========================
CREATE TABLE veiculos (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    placa       VARCHAR(8) NOT NULL UNIQUE,
    marca       VARCHAR(100) NOT NULL,
    modelo      VARCHAR(100) NOT NULL,
    ano         INTEGER NOT NULL,
    cor         VARCHAR(50),
    chassi      VARCHAR(17),
    cliente_id  UUID NOT NULL REFERENCES clientes(id),
    ativo       BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em   TIMESTAMP NOT NULL DEFAULT NOW(),
    atualizado_em TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ano_valido CHECK (ano >= 1900 AND ano <= EXTRACT(YEAR FROM NOW()) + 1)
);

-- ========================
-- TABELA: servicos
-- ========================
CREATE TABLE servicos (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    nome                VARCHAR(255) NOT NULL,
    descricao           TEXT,
    valor               NUMERIC(10,2) NOT NULL,
    tempo_estimado_min  INTEGER NOT NULL DEFAULT 60,
    ativo               BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em           TIMESTAMP NOT NULL DEFAULT NOW(),
    atualizado_em       TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT valor_positivo CHECK (valor >= 0),
    CONSTRAINT tempo_positivo CHECK (tempo_estimado_min > 0)
);

-- ========================
-- TABELA: pecas
-- ========================
CREATE TABLE pecas (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    codigo              VARCHAR(50) NOT NULL UNIQUE,
    nome                VARCHAR(255) NOT NULL,
    descricao           TEXT,
    valor_unitario      NUMERIC(10,2) NOT NULL,
    quantidade_estoque  INTEGER NOT NULL DEFAULT 0,
    quantidade_minima   INTEGER NOT NULL DEFAULT 1,
    unidade_medida      VARCHAR(20) NOT NULL DEFAULT 'UN',
    ativo               BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em           TIMESTAMP NOT NULL DEFAULT NOW(),
    atualizado_em       TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT valor_peca_positivo CHECK (valor_unitario >= 0),
    CONSTRAINT estoque_nao_negativo CHECK (quantidade_estoque >= 0)
);

-- ========================
-- TABELA: ordens_servico
-- ========================
CREATE TABLE ordens_servico (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    numero          BIGSERIAL UNIQUE,
    cliente_id      UUID NOT NULL REFERENCES clientes(id),
    veiculo_id      UUID NOT NULL REFERENCES veiculos(id),
    status          status_os NOT NULL DEFAULT 'RECEBIDA',
    descricao       TEXT,
    observacoes     TEXT,
    valor_total     NUMERIC(10,2) NOT NULL DEFAULT 0,
    aprovado_em     TIMESTAMP,
    iniciado_em     TIMESTAMP,
    finalizado_em   TIMESTAMP,
    entregue_em     TIMESTAMP,
    criado_em       TIMESTAMP NOT NULL DEFAULT NOW(),
    atualizado_em   TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT valor_total_nao_negativo CHECK (valor_total >= 0)
);

-- ========================
-- TABELA: os_servicos
-- ========================
CREATE TABLE os_servicos (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    os_id           UUID NOT NULL REFERENCES ordens_servico(id),
    servico_id      UUID NOT NULL REFERENCES servicos(id),
    quantidade      INTEGER NOT NULL DEFAULT 1,
    valor_unitario  NUMERIC(10,2) NOT NULL,
    valor_total     NUMERIC(10,2) NOT NULL,
    observacao      TEXT,
    criado_em       TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT qtd_servico_positiva CHECK (quantidade > 0)
);

-- ========================
-- TABELA: os_pecas
-- ========================
CREATE TABLE os_pecas (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    os_id           UUID NOT NULL REFERENCES ordens_servico(id),
    peca_id         UUID NOT NULL REFERENCES pecas(id),
    quantidade      INTEGER NOT NULL DEFAULT 1,
    valor_unitario  NUMERIC(10,2) NOT NULL,
    valor_total     NUMERIC(10,2) NOT NULL,
    criado_em       TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT qtd_peca_positiva CHECK (quantidade > 0)
);

-- ========================
-- TABELA: os_historico
-- ========================
CREATE TABLE os_historico (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    os_id           UUID NOT NULL REFERENCES ordens_servico(id),
    status_anterior status_os,
    status_novo     status_os NOT NULL,
    observacao      TEXT,
    alterado_por    UUID REFERENCES usuarios(id),
    criado_em       TIMESTAMP NOT NULL DEFAULT NOW()
);

-- ========================
-- ÍNDICES DE PERFORMANCE
-- ========================
CREATE INDEX idx_clientes_documento ON clientes(documento);
CREATE INDEX idx_clientes_email ON clientes(email);
CREATE INDEX idx_veiculos_placa ON veiculos(placa);
CREATE INDEX idx_veiculos_cliente ON veiculos(cliente_id);
CREATE INDEX idx_os_cliente ON ordens_servico(cliente_id);
CREATE INDEX idx_os_veiculo ON ordens_servico(veiculo_id);
CREATE INDEX idx_os_status ON ordens_servico(status);
CREATE INDEX idx_os_numero ON ordens_servico(numero);
CREATE INDEX idx_os_servicos_os ON os_servicos(os_id);
CREATE INDEX idx_os_pecas_os ON os_pecas(os_id);
CREATE INDEX idx_os_historico_os ON os_historico(os_id);
CREATE INDEX idx_pecas_codigo ON pecas(codigo);

-- ========================
-- TRIGGER: atualizado_em automático
-- ========================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.atualizado_em = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_clientes_updated_at BEFORE UPDATE ON clientes
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_veiculos_updated_at BEFORE UPDATE ON veiculos
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_servicos_updated_at BEFORE UPDATE ON servicos
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_pecas_updated_at BEFORE UPDATE ON pecas
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_os_updated_at BEFORE UPDATE ON ordens_servico
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_usuarios_updated_at BEFORE UPDATE ON usuarios
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
