package com.oficina.support;

import com.oficina.entity.*;
import com.oficina.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public final class SagaFixtures {
    private SagaFixtures() {}

    public static Cliente cliente(ClienteRepository clientes) {
        return clientes.findByDocumento("39053344705").orElseGet(() -> clientes.save(Fixtures.cliente(null)));
    }

    /** Valid Mercosul plate (ValidadorPlaca: ^[A-Z]{3}\d[A-Z\d]\d{2}$), random enough for the unique index. */
    public static UUID veiculo(JdbcTemplate jdbc, UUID clienteId) {
        UUID id = UUID.randomUUID();
        var r = java.util.concurrent.ThreadLocalRandom.current();
        String placa = "TST" + r.nextInt(10) + (char) ('A' + r.nextInt(26)) + String.format("%02d", r.nextInt(100));
        jdbc.update("INSERT INTO veiculos(id,placa,marca,modelo,ano,cliente_id) VALUES (?,?,'Teste','Teste',2025,?)",
                id, placa, clienteId);
        return id;
    }

    public static UUID peca(JdbcTemplate jdbc, int estoque, String valor) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pecas(id,codigo,nome,valor_unitario,quantidade_estoque) VALUES (?,?,?,?,?)",
                id, "P-" + id.toString().substring(0, 8), "Peça teste", new BigDecimal(valor), estoque);
        return id;
    }

    public static UUID servico(JdbcTemplate jdbc, String valor) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO servicos(id,nome,valor) VALUES (?,?,?)", id, "Serviço teste", new BigDecimal(valor));
        return id;
    }

    public static UUID ordem(TransactionTemplate tx, OrdemServicoRepository ordens, VeiculoRepository veiculos,
                             PecaRepository pecas, ServicoRepository servicos, Cliente cliente, UUID veiculoId,
                             StatusOrdemServico status, Map<UUID, Integer> pecasQtd, Map<UUID, Integer> servicosQtd) {
        return tx.execute(st -> {
            OrdemServico os = Fixtures.ordem(cliente, status);
            os.setVeiculo(veiculos.getReferenceById(veiculoId));
            pecasQtd.forEach((id, q) -> {
                Peca p = pecas.findById(id).orElseThrow();
                os.adicionarPeca(OsPecaItem.builder().peca(p).quantidade(q).valorUnitario(p.getValorUnitario())
                        .valorTotal(p.getValorUnitario().multiply(BigDecimal.valueOf(q))).build());
            });
            servicosQtd.forEach((id, q) -> {
                Servico s = servicos.findById(id).orElseThrow();
                os.adicionarServico(OsServicoItem.builder().servico(s).quantidade(q).valorUnitario(s.getValor())
                        .valorTotal(s.getValor().multiply(BigDecimal.valueOf(q))).build());
            });
            os.recalcularValorTotal();
            return ordens.saveAndFlush(os).getId();
        });
    }
}
