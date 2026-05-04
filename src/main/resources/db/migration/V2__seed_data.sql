-- ============================================================
-- V2__seed_data.sql
-- Dados iniciais do sistema
-- ============================================================

-- Usuário admin padrão
-- Senha: Admin@123 (BCrypt hash)
INSERT INTO usuarios (id, email, senha, role, ativo)
VALUES (
    uuid_generate_v4(),
    'admin@oficina.com',
    '$2a$12$wvBjDGlSmRnjw.l1V2V2PeKlhHFCEbmG7bEWRqxdJmEbJ5A7kqPoy',
    'ADMIN',
    TRUE
);

-- Serviços padrão da oficina
INSERT INTO servicos (nome, descricao, valor, tempo_estimado_min) VALUES
('Troca de Óleo',            'Troca de óleo do motor + filtro',                           8000,  60),
('Alinhamento',              'Alinhamento das rodas dianteiras e traseiras',               12000, 90),
('Balanceamento',            'Balanceamento das 4 rodas',                                  8000,  60),
('Revisão Geral',            'Revisão completa do veículo',                                35000, 240),
('Troca de Pastilha',        'Substituição das pastilhas de freio',                        18000, 120),
('Troca de Pneu',            'Substituição de pneu (mão de obra)',                         5000,  30),
('Diagnóstico Eletrônico',   'Leitura de falhas via OBD2',                                 8000,  30),
('Troca de Filtro de Ar',    'Substituição do filtro de ar do motor',                      4000,  30),
('Troca de Correia Dentada', 'Substituição da correia dentada com kit tensor',             45000, 180),
('Higienização do A/C',      'Limpeza e higienização do sistema de ar-condicionado',       15000, 60);

-- Peças padrão em estoque
INSERT INTO pecas (codigo, nome, descricao, valor_unitario, quantidade_estoque, quantidade_minima, unidade_medida) VALUES
('OL-5W30-1L',    'Óleo Motor 5W30 1L',           'Óleo sintético 5W30',               2500,  50, 10, 'UN'),
('FILT-OL-001',   'Filtro de Óleo Universal',      'Filtro de óleo para carros nacionais', 1500, 30,  5, 'UN'),
('PAST-FRONT-001','Pastilha Freio Dianteira',       'Jogo pastilha dianteira',           8000,  15,  3, 'JG'),
('PAST-TRAS-001', 'Pastilha Freio Traseira',        'Jogo pastilha traseira',            7000,  15,  3, 'JG'),
('FILT-AR-001',   'Filtro de Ar Universal',         'Filtro de ar para motores 1.0-2.0', 2000, 20,  5, 'UN'),
('CORR-DENT-001', 'Correia Dentada + Kit',          'Correia dentada com tensor e correia acessórios', 18000, 10, 2, 'KT'),
('VELA-NGK-001',  'Vela de Ignição NGK',            'Vela iridium NGK',                  3500,  40,  8, 'UN'),
('LIQREFRIG-001', 'Líquido de Arrefecimento 1L',    'Anticongelante concentrado',        1800,  25,  5, 'UN'),
('FILT-COMB-001', 'Filtro de Combustível',          'Filtro de combustível universal',   2500,  20,  4, 'UN'),
('AMORT-FRONT-001','Amortecedor Dianteiro (par)',    'Par de amortecedores dianteiros',   18000,  8,  2, 'PR');

-- Nota: valores em centavos (ex: 2500 = R$ 25,00)
