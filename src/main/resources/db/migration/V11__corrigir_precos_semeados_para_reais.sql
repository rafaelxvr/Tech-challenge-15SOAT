-- ============================================================
-- V11__corrigir_precos_semeados_para_reais.sql
-- O seed V2 gravou valores em centavos (ex.: 12000) em colunas NUMERIC(10,2)
-- cujo contrato é reais decimais (12000 = R$ 12.000,00 em vez de R$ 120,00).
-- Corrige somente os 10 serviços e as 10 peças do seed, identificados
-- pela impressão digital completa da linha gravada pelo V2 (servicos: nome,
-- descricao, tempo_estimado_min e valor; pecas: codigo, nome, descricao, valor,
-- quantidade_estoque, quantidade_minima e unidade_medida). Nome ou código
-- isolados não bastam: servicos.nome não é UNIQUE e um registro do operador
-- pode repetir nome/código e valor. Linha editada depois do seed (qualquer
-- coluna diferente) não é tocada, e a migration é idempotente: após rodar, o
-- valor deixa de casar com o filtro.
-- Não altera ordens de serviço já criadas (snapshots de preço próprios).
--
-- Rollback manual (não automático): multiplicar por 100 os mesmos registros
-- identificados pela mesma impressão digital completa (nome, descricao,
-- tempo_estimado_min e valor já corrigido), ex.:
--   UPDATE servicos SET valor = valor * 100
--   WHERE nome = 'Alinhamento' AND descricao = '...' AND tempo_estimado_min = 90
--     AND valor = 120.00; (repetir por linha; nunca só por nome/valor).
-- ============================================================

UPDATE servicos AS s
SET valor = c.novo
FROM (VALUES
    ('Troca de Óleo', 'Troca de óleo do motor + filtro', 60, 8000.00, 80.00),
    ('Alinhamento', 'Alinhamento das rodas dianteiras e traseiras', 90, 12000.00, 120.00),
    ('Balanceamento', 'Balanceamento das 4 rodas', 60, 8000.00, 80.00),
    ('Revisão Geral', 'Revisão completa do veículo', 240, 35000.00, 350.00),
    ('Troca de Pastilha', 'Substituição das pastilhas de freio', 120, 18000.00, 180.00),
    ('Troca de Pneu', 'Substituição de pneu (mão de obra)', 30, 5000.00, 50.00),
    ('Diagnóstico Eletrônico', 'Leitura de falhas via OBD2', 30, 8000.00, 80.00),
    ('Troca de Filtro de Ar', 'Substituição do filtro de ar do motor', 30, 4000.00, 40.00),
    ('Troca de Correia Dentada', 'Substituição da correia dentada com kit tensor', 180, 45000.00, 450.00),
    ('Higienização do A/C', 'Limpeza e higienização do sistema de ar-condicionado', 60, 15000.00, 150.00)
) AS c(nome, descricao, tempo_estimado_min, antigo, novo)
WHERE s.nome = c.nome
  AND s.descricao = c.descricao
  AND s.tempo_estimado_min = c.tempo_estimado_min
  AND s.valor = c.antigo;

UPDATE pecas AS p
SET valor_unitario = c.novo
FROM (VALUES
    ('OL-5W30-1L', 'Óleo Motor 5W30 1L', 'Óleo sintético 5W30', 2500.00, 25.00, 50, 10, 'UN'),
    ('FILT-OL-001', 'Filtro de Óleo Universal', 'Filtro de óleo para carros nacionais', 1500.00, 15.00, 30, 5, 'UN'),
    ('PAST-FRONT-001', 'Pastilha Freio Dianteira', 'Jogo pastilha dianteira', 8000.00, 80.00, 15, 3, 'JG'),
    ('PAST-TRAS-001', 'Pastilha Freio Traseira', 'Jogo pastilha traseira', 7000.00, 70.00, 15, 3, 'JG'),
    ('FILT-AR-001', 'Filtro de Ar Universal', 'Filtro de ar para motores 1.0-2.0', 2000.00, 20.00, 20, 5, 'UN'),
    ('CORR-DENT-001', 'Correia Dentada + Kit', 'Correia dentada com tensor e correia acessórios', 18000.00, 180.00, 10, 2, 'KT'),
    ('VELA-NGK-001', 'Vela de Ignição NGK', 'Vela iridium NGK', 3500.00, 35.00, 40, 8, 'UN'),
    ('LIQREFRIG-001', 'Líquido de Arrefecimento 1L', 'Anticongelante concentrado', 1800.00, 18.00, 25, 5, 'UN'),
    ('FILT-COMB-001', 'Filtro de Combustível', 'Filtro de combustível universal', 2500.00, 25.00, 20, 4, 'UN'),
    ('AMORT-FRONT-001', 'Amortecedor Dianteiro (par)', 'Par de amortecedores dianteiros', 18000.00, 180.00, 8, 2, 'PR')
) AS c(codigo, nome, descricao, antigo, novo, quantidade_estoque, quantidade_minima, unidade_medida)
WHERE p.codigo = c.codigo
  AND p.nome = c.nome
  AND p.descricao = c.descricao
  AND p.valor_unitario = c.antigo
  AND p.quantidade_estoque = c.quantidade_estoque
  AND p.quantidade_minima = c.quantidade_minima
  AND p.unidade_medida = c.unidade_medida;
