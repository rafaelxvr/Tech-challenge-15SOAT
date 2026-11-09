package com.oficina.repository;

import com.oficina.support.PostgresIntegrationSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrecosSemeadosMigrationTest extends PostgresIntegrationSupport {

    /** Banco descartável no container compartilhado: outros testes limpam as tabelas do banco principal. */
    private JdbcTemplate novoBanco(String targetVersion) {
        String database = "v11_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + database);
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        DriverManagerDataSource source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        var config = Flyway.configure().dataSource(source);
        if (targetVersion != null) config.target(targetVersion);
        config.load().migrate();
        return new JdbcTemplate(source);
    }

    @Test void catalogoSemeadoFicaEmReaisDecimais() {
        JdbcTemplate jdbc = novoBanco(null);
        assertThat(servicoValor(jdbc, "Troca de Óleo")).isEqualByComparingTo("80.00");
        assertThat(servicoValor(jdbc, "Alinhamento")).isEqualByComparingTo("120.00");
        assertThat(servicoValor(jdbc, "Troca de Correia Dentada")).isEqualByComparingTo("450.00");
        assertThat(pecaValor(jdbc, "OL-5W30-1L")).isEqualByComparingTo("25.00");
        assertThat(pecaValor(jdbc, "AMORT-FRONT-001")).isEqualByComparingTo("180.00");
    }

    @Test void orcamentoDoCenarioC1CustaCentoESetentaReais() {
        JdbcTemplate jdbc = novoBanco(null);
        // Cenário QA C1: primeiro serviço por nome (Alinhamento) x1 + OL-5W30-1L x2.
        String primeiro = jdbc.queryForObject("SELECT nome FROM servicos ORDER BY nome LIMIT 1", String.class);
        assertThat(primeiro).isEqualTo("Alinhamento");
        BigDecimal total = servicoValor(jdbc, primeiro).add(pecaValor(jdbc, "OL-5W30-1L").multiply(BigDecimal.valueOf(2)));
        assertThat(total).isEqualByComparingTo("170.00");
    }

    @Test void upgradeCorrigeSomenteValoresIdentificadosDoSeedEPreservaEdicoes() {
        JdbcTemplate legado = novoBanco("10");
        DriverManagerDataSource source = (DriverManagerDataSource) legado.getDataSource();
        assertThat(servicoValor(legado, "Alinhamento")).isEqualByComparingTo("12000.00");
        assertThat(pecaValor(legado, "OL-5W30-1L")).isEqualByComparingTo("2500.00");
        // Preço já corrigido manualmente por operador: não pode ser dividido de novo.
        legado.update("UPDATE servicos SET valor = 99.90 WHERE nome = 'Balanceamento'");
        legado.update("INSERT INTO servicos(nome, descricao, valor, tempo_estimado_min) VALUES ('Serviço Novo','x',12000.00,10)");

        Flyway.configure().dataSource(source).load().migrate();

        assertThat(servicoValor(legado, "Alinhamento")).isEqualByComparingTo("120.00");
        assertThat(pecaValor(legado, "OL-5W30-1L")).isEqualByComparingTo("25.00");
        assertThat(servicoValor(legado, "Balanceamento")).isEqualByComparingTo("99.90");
        assertThat(servicoValor(legado, "Serviço Novo")).isEqualByComparingTo("12000.00");
        assertThat(legado.queryForObject("SELECT COUNT(*) FROM servicos WHERE valor >= 1000 AND nome <> 'Serviço Novo'", Integer.class)).isZero();
    }

    private static BigDecimal servicoValor(JdbcTemplate db, String nome) {
        return db.queryForObject("SELECT valor FROM servicos WHERE nome = ?", BigDecimal.class, nome);
    }

    private static BigDecimal pecaValor(JdbcTemplate db, String codigo) {
        return db.queryForObject("SELECT valor_unitario FROM pecas WHERE codigo = ?", BigDecimal.class, codigo);
    }
}
