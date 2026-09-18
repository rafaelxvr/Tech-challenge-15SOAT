package com.oficina.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ClienteIdentityAuditRepository {
    private final JdbcTemplate jdbcTemplate;

    public void registrar(UUID clienteId, UUID staffId, Set<String> campos,
                          long versaoAnterior, long versaoNova, Instant agora) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO cliente_identidade_auditoria
                    (id, cliente_id, staff_id, campos, versao_anterior, versao_nova, ocorrido_em)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, clienteId);
            statement.setObject(3, staffId);
            statement.setArray(4, connection.createArrayOf("text", campos.stream().sorted().toArray(String[]::new)));
            statement.setLong(5, versaoAnterior);
            statement.setLong(6, versaoNova);
            statement.setTimestamp(7, Timestamp.from(agora));
            return statement;
        });
    }
}
