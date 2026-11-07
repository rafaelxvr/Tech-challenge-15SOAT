-- Minimal data at the V8 schema state, applied by scripts/verify-phase4-db.ps1 before V9/V10.
-- Proves the upgrade keeps existing orders and lets the saga tables reference them.
INSERT INTO clientes (id, nome, tipo_documento, documento, email, telefone)
VALUES ('00000000-0000-0000-0000-0000000000c1', 'Cliente Fixture V8', 'CPF', '00000000191', 'fixture@example.test', '11999990000');
INSERT INTO veiculos (id, placa, marca, modelo, ano, cliente_id)
VALUES ('00000000-0000-0000-0000-0000000000a1', 'FIX1V08', 'Marca', 'Modelo', 2020, '00000000-0000-0000-0000-0000000000c1');
INSERT INTO ordens_servico (id, cliente_id, veiculo_id, status, descricao)
VALUES ('00000000-0000-0000-0000-0000000000b1', '00000000-0000-0000-0000-0000000000c1',
        '00000000-0000-0000-0000-0000000000a1', 'RECEBIDA', 'OS fixture V8');
