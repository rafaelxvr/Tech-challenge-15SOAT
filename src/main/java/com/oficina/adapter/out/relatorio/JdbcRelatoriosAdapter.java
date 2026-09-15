package com.oficina.adapter.out.relatorio;

import com.oficina.application.relatorio.*;
import com.oficina.entity.StatusOrdemServico;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

@Repository
public class JdbcRelatoriosAdapter implements RelatoriosPort {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;
    private final String periodoSql = sql("relatorio-periodo.sql");
    private final String statusSql = sql("relatorio-status-atual.sql");

    public JdbcRelatoriosAdapter(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(2);
    }

    @Override
    public RelatorioPeriodo consultar(LocalDate inicio, LocalDate fimExclusive, ZoneId zona) {
        Objects.requireNonNull(inicio);
        Objects.requireNonNull(fimExclusive);
        Objects.requireNonNull(zona);
        if (!fimExclusive.isAfter(inicio)) throw new IllegalArgumentException("fimExclusive deve ser posterior a inicio");
        var parameters = Map.of("inicio", inicio.atStartOfDay(zona).toOffsetDateTime(),
                "fim", fimExclusive.atStartOfDay(zona).toOffsetDateTime());
        return read(() -> named.query(periodoSql, parameters, rs -> {
            var duracoes = new EnumMap<StatusOrdemServico, DuracaoStatus>(StatusOrdemServico.class);
            long criadas = 0, elegiveis = 0, excluidas = 0;
            while (rs.next()) {
                criadas = rs.getLong("criadas");
                elegiveis = rs.getLong("elegiveis");
                excluidas = rs.getLong("excluidas");
                duracoes.put(StatusOrdemServico.valueOf(rs.getString("status")),
                        new DuracaoStatus(rs.getBigDecimal("total_segundos"), rs.getLong("amostras")));
            }
            return new RelatorioPeriodo(criadas, elegiveis, excluidas, duracoes);
        }));
    }

    @Override
    public List<StatusAtual> statusAtual(Instant agora) {
        Objects.requireNonNull(agora);
        return read(() -> List.copyOf(named.query(statusSql, Map.of("agora", agora.atOffset(ZoneOffset.UTC)),
                (rs, row) -> new StatusAtual(StatusOrdemServico.valueOf(rs.getString("status")),
                        rs.getLong("quantidade"), rs.getBigDecimal("idade_maxima_segundos"),
                        rs.getLong("amostras_idade"), rs.getLong("idades_desconhecidas")))));
    }

    private <T> T read(Supplier<T> query) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '2s'");
            return query.get();
        });
    }

    private static String sql(String name) {
        try {
            return new ClassPathResource("queries/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
