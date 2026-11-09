-- ============================================================
-- V11__corrigir_precos_semeados_para_reais.sql
-- O seed V2 gravou valores em centavos (ex.: 12000) em colunas NUMERIC(10,2)
-- cujo contrato é reais decimais (12000 = R$ 12.000,00 em vez de R$ 120,00).
-- Corrige somente os 10 serviços e as 10 peças do seed, identificados por
-- nome/código E pelo valor exato semeado (WHERE ... valor = <valor do V2>).
-- Registros editados depois do seed (valor diferente) não são tocados, e a
-- migration é idempotente: após rodar, o valor deixa de casar com o filtro.
-- Não altera ordens de serviço já criadas (snapshots de preço próprios).
--
-- Rollback manual (não automático): multiplicar por 100 os mesmos registros
-- identificados, ex.: UPDATE servicos SET valor = valor * 100
--   WHERE nome = 'Alinhamento' AND valor = 120.00; (repetir por linha).
-- ============================================================

UPDATE servicos AS s
SET valor = c.novo
FROM (VALUES
    ('Troca de Óleo',            8000.00,  80.00),
    ('Alinhamento',             12000.00, 120.00),
    ('Balanceamento',            8000.00,  80.00),
    ('Revisão Geral',           35000.00, 350.00),
    ('Troca de Pastilha',       18000.00, 180.00),
    ('Troca de Pneu',            5000.00,  50.00),
    ('Diagnóstico Eletrônico',   8000.00,  80.00),
    ('Troca de Filtro de Ar',    4000.00,  40.00),
    ('Troca de Correia Dentada',45000.00, 450.00),
    ('Higienização do A/C',     15000.00, 150.00)
) AS c(nome, antigo, novo)
WHERE s.nome = c.nome AND s.valor = c.antigo;

UPDATE pecas AS p
SET valor_unitario = c.novo
FROM (VALUES
    ('OL-5W30-1L',     2500.00,  25.00),
    ('FILT-OL-001',    1500.00,  15.00),
    ('PAST-FRONT-001', 8000.00,  80.00),
    ('PAST-TRAS-001',  7000.00,  70.00),
    ('FILT-AR-001',    2000.00,  20.00),
    ('CORR-DENT-001', 18000.00, 180.00),
    ('VELA-NGK-001',   3500.00,  35.00),
    ('LIQREFRIG-001',  1800.00,  18.00),
    ('FILT-COMB-001',  2500.00,  25.00),
    ('AMORT-FRONT-001',18000.00, 180.00)
) AS c(codigo, antigo, novo)
WHERE p.codigo = c.codigo AND p.valor_unitario = c.antigo;
