-- Script de inicialização do banco de dados
-- Executado automaticamente pelo PostgreSQL na primeira vez

-- Garante que a extensão uuid-ossp esteja disponível
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Garante que a extensão para validação de dados esteja disponível
CREATE EXTENSION IF NOT EXISTS "unaccent";

-- Criação do schema principal (caso não exista)
CREATE SCHEMA IF NOT EXISTS public;

GRANT ALL ON SCHEMA public TO oficina;
