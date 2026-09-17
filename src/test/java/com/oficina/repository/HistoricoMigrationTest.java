package com.oficina.repository;

import com.oficina.support.PostgresIntegrationSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class HistoricoMigrationTest extends PostgresIntegrationSupport {
    @Test void upgradePreservesUnknownTimesActorsAndOrderingWithoutInventedBackfill() {
        // Dedicated disposable database in the shared test container, never the application database.
        String database = "a2_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE DATABASE " + database);
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        DriverManagerDataSource source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate legacy = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("5").load().migrate();
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID staff = legacy.queryForObject("SELECT id FROM usuarios WHERE email='admin@oficina.com'", UUID.class);
        LocalDateTime oldTime = LocalDateTime.of(2025, 1, 5, 12, 34, 56);
        legacy.update("INSERT INTO clientes(id,nome,tipo_documento,documento,email,telefone) VALUES (?,'Legado','CPF','39053344705','legacy@example.invalid','11999999999')", customer);
        legacy.update("INSERT INTO veiculos(id,placa,marca,modelo,ano,cliente_id) VALUES (?,'LEG1A23','Teste','Teste',2025,?)", vehicle, customer);
        legacy.update("INSERT INTO ordens_servico(id,cliente_id,veiculo_id,status,criado_em) VALUES (?,?,?,'EM_DIAGNOSTICO',?)", order, customer, vehicle, oldTime);
        legacy.update("INSERT INTO os_historico(os_id,status_novo,alterado_por,criado_em) VALUES (?,'RECEBIDA',?,?)", order, staff, oldTime);
        legacy.update("INSERT INTO os_historico(os_id,status_anterior,status_novo,criado_em) VALUES (?,'RECEBIDA','EM_DIAGNOSTICO',?)", order, oldTime);

        Flyway.configure().dataSource(source).load().migrate();

        assertThat(legacy.queryForList("SELECT ator_tipo FROM os_historico ORDER BY ator_tipo", String.class))
                .containsExactly("LEGACY_UNKNOWN", "STAFF");
        assertThat(legacy.queryForObject("SELECT COUNT(*) FROM os_historico WHERE ocorrido_em IS NULL AND sequencia IS NULL AND criado_em=?", Integer.class, oldTime)).isEqualTo(2);
        assertThat(legacy.queryForObject("SELECT criado_em FROM ordens_servico WHERE id=?", LocalDateTime.class, order)).isEqualTo(oldTime);
        assertThat(legacy.queryForObject("SELECT criado_em_utc FROM ordens_servico WHERE id=?", Object.class, order)).isNull();
        assertThat(legacy.queryForObject("SELECT historico_completo_desde_inicio FROM ordens_servico WHERE id=?", Boolean.class, order)).isFalse();
        assertThat(legacy.queryForObject("SELECT sequencia_historico FROM ordens_servico WHERE id=?", Long.class, order)).isZero();
        assertThat(legacy.queryForObject("SELECT versao FROM ordens_servico WHERE id=?", Long.class, order)).isZero();

        // Explicit UTC only for newly created synthetic history; old rows stay unresolved.
        legacy.update("INSERT INTO os_historico(os_id,status_novo,ator_tipo,ator_cliente_id,ocorrido_em,sequencia,criado_em) VALUES (?,'EM_EXECUCAO','CUSTOMER',?,'2026-09-15T12:00:00Z',1,'2026-09-15 12:00:00')", order, customer);
        assertThatThrownBy(() -> legacy.update("INSERT INTO os_historico(os_id,status_novo,ator_tipo,ocorrido_em,sequencia) VALUES (?,'EM_EXECUCAO','SYSTEM','2026-09-15T12:00:00Z',1)", order))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_os_historico_os_sequencia");
        assertThatThrownBy(() -> legacy.update("INSERT INTO os_historico(os_id,status_novo,ator_tipo,alterado_por,ator_cliente_id) VALUES (?,'EM_EXECUCAO','CUSTOMER',?,?)", order, staff, customer))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_os_historico_ator");
        assertThatThrownBy(() -> legacy.update("INSERT INTO os_historico(os_id,status_novo,ator_tipo,ator_cliente_id) VALUES (?,'EM_EXECUCAO','CUSTOMER',?)", order, staff))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("foreign key");
        assertThatThrownBy(() -> legacy.update("INSERT INTO os_historico(os_id,status_novo,ator_tipo,alterado_por) VALUES (?,'EM_EXECUCAO','STAFF',?)", order, customer))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("foreign key");
        assertThatThrownBy(() -> legacy.update("INSERT INTO os_historico(os_id,status_novo,ator_tipo,sequencia) VALUES (?,'EM_EXECUCAO','SYSTEM',2)", order))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_os_historico_canonico");
        assertThat(legacy.queryForObject("SELECT COUNT(*) FROM os_historico WHERE sequencia IS NULL", Integer.class)).isEqualTo(2);
    }
}
