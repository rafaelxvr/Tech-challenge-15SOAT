package com.oficina.adapter.out.relatorio;

import com.oficina.application.relatorio.RelatoriosPort;
import com.oficina.controller.RelatoriosAdminController;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.*;
import java.util.UUID;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(JdbcRelatoriosAdapter.class)
class RelatorioPeriodoTest extends PostgresIntegrationSupport {
    @Autowired RelatoriosPort reports;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    UUID customer;
    UUID vehicle;
    final LocalDate day = LocalDate.of(2026, 9, 15);
    final ZoneId zone = ZoneId.of("America/Sao_Paulo");

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE outbox_recuperacoes,outbox_eventos,os_historico,os_pecas,os_servicos,ordens_servico RESTART IDENTITY CASCADE");
        customer = UUID.randomUUID();
        vehicle = UUID.randomUUID();
        jdbc.update("INSERT INTO clientes(id,nome,tipo_documento,documento,email,telefone) VALUES (?,'Reports','CPF',?,'reports@example.invalid','11999999999')", customer, customer.toString().substring(0,18));
        jdbc.update("INSERT INTO veiculos(id,placa,marca,modelo,ano,cliente_id) VALUES (?,?,'Teste','Teste',2025,?)", vehicle, vehicle.toString().substring(0,8), customer);
    }

    @Test void deliveredCohortIncludesReworkAndExcludesUnfinishedAndIncomplete() {
        referenceFixture();
        var report = reports.consultar(day, day.plusDays(1), zone);
        assertThat(report.elegiveis()).isEqualTo(2);
        assertThat(report.excluidas()).isEqualTo(1);
        assertThat(report.criadas()).isEqualTo(4);
        assertThat(report.duracoes().get(StatusOrdemServico.EM_DIAGNOSTICO).totalSegundos()).isEqualByComparingTo("3600");
        assertThat(report.duracoes().get(StatusOrdemServico.EM_DIAGNOSTICO).amostras()).isEqualTo(2);
        assertThat(report.duracoes().get(StatusOrdemServico.EM_EXECUCAO).totalSegundos()).isEqualByComparingTo("6000");
        assertThat(report.duracoes().get(StatusOrdemServico.FINALIZADA).totalSegundos()).isEqualByComparingTo("2400");
    }

    @Test void referenceFixtureThroughHttpHasThirtyFiftyTwentyMinuteMeans() throws Exception {
        referenceFixture();
        MockMvcBuilders.standaloneSetup(new RelatoriosAdminController(reports)).build()
                .perform(get("/api/admin/relatorios/ordens").contextPath("/api").param("inicio",day.toString()).param("fimExclusive",day.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duracoes.EM_DIAGNOSTICO.mediaSegundos").value("1800"))
                .andExpect(jsonPath("$.data.duracoes.EM_EXECUCAO.mediaSegundos").value("3000"))
                .andExpect(jsonPath("$.data.duracoes.FINALIZADA.mediaSegundos").value("1200"))
                .andExpect(jsonPath("$.data.elegiveis").value(2))
                .andExpect(jsonPath("$.data.excluidas").value(1));
    }

    @Test void emptyAndUnknownHistoriesHaveNoSamplesAndUnknownDeliveryIsExplicitlyExcluded() {
        var empty = reports.consultar(day,day.plusDays(1),zone);
        assertThat(empty.elegiveis()).isZero();
        assertThat(empty.excluidas()).isZero();
        assertThat(empty.duracoes()).hasSize(6).allSatisfy((s,d) -> {
            assertThat(d.totalSegundos()).isZero();
            assertThat(d.amostras()).isZero();
        });
        UUID unknown = order("2026-09-15T04:00:00Z",false,new String[]{"ENTREGUE"},0);
        jdbc.update("UPDATE os_historico SET ocorrido_em=NULL,sequencia=NULL WHERE os_id=?", unknown);
        jdbc.update("UPDATE ordens_servico SET criado_em_utc=NULL,sequencia_historico=0 WHERE id=?", unknown);
        var report = reports.consultar(day,day.plusDays(1),zone);
        assertThat(report.elegiveis()).isZero();
        assertThat(report.excluidas()).isEqualTo(1);
        assertThat(report.criadas()).isZero();
        assertThat(report.duracoes().values()).allSatisfy(d -> assertThat(d.amostras()).isZero());
    }

    @Test void midnightUsesBrazilHalfOpenCohortWithoutTruncatingEarlierLifecycle() {
        String[] statuses = {"RECEBIDA","EM_DIAGNOSTICO","AGUARDANDO_APROVACAO","EM_EXECUCAO","FINALIZADA","ENTREGUE"};
        order("2026-09-15T02:00:00Z",true,statuses,0,5,15,20,40,60); // exactly Brazil start
        order("2026-09-16T02:00:00Z",true,statuses,0,5,15,20,40,60); // exactly exclusive end
        var report = reports.consultar(day,day.plusDays(1),zone);
        assertThat(report.elegiveis()).isEqualTo(1);
        assertThat(report.criadas()).isEqualTo(1); // independently: the second order only
        assertThat(report.duracoes().get(StatusOrdemServico.EM_DIAGNOSTICO).totalSegundos()).isEqualByComparingTo("600");
    }

    @Test void tiedNegativeGappedDiscontinuousAndMismatchedHistoriesAreExcluded() {
        String[] statuses = {"RECEBIDA","EM_DIAGNOSTICO","AGUARDANDO_APROVACAO","EM_EXECUCAO","FINALIZADA","ENTREGUE"};
        order("2026-09-15T04:00:00Z",true,statuses,0,5,5,20,40,60);
        order("2026-09-15T04:00:00Z",true,statuses,0,10,5,20,40,60);
        UUID gap = order("2026-09-15T04:00:00Z",true,statuses,0,5,10,20,40,60);
        jdbc.update("DELETE FROM os_historico WHERE os_id=? AND sequencia=3", gap);
        UUID discontinuous = order("2026-09-15T04:00:00Z",true,statuses,0,5,10,20,40,60);
        jdbc.update("UPDATE os_historico SET status_anterior='RECEBIDA' WHERE os_id=? AND sequencia=3", discontinuous);
        UUID mismatch = order("2026-09-15T04:00:00Z",true,statuses,0,5,10,20,40,60);
        jdbc.update("UPDATE ordens_servico SET sequencia_historico=7 WHERE id=?", mismatch);
        UUID wrongCreation = order("2026-09-15T04:00:00Z",true,statuses,0,5,10,20,40,60);
        jdbc.update("UPDATE ordens_servico SET criado_em_utc=criado_em_utc-interval '1 minute' WHERE id=?", wrongCreation);
        order("2026-09-15T04:00:00Z",true,new String[]{"RECEBIDA","ENTREGUE"},0,5);
        var report = reports.consultar(day,day.plusDays(1),zone);
        assertThat(report.elegiveis()).isZero();
        assertThat(report.excluidas()).isEqualTo(7);
        assertThat(report.duracoes().values()).allSatisfy(d -> assertThat(d.amostras()).isZero());
    }

    @Test void currentAgeUsesLatestCanonicalTransitionAndReportsUnknownSeparately() {
        order("2026-09-15T04:00:00Z",true,new String[]{"RECEBIDA","EM_DIAGNOSTICO"},0,5);
        order("2026-09-15T04:00:00Z",false,new String[]{"EM_DIAGNOSTICO"},30);
        UUID unknown = order("2026-09-15T04:00:00Z",false,new String[]{"EM_DIAGNOSTICO"},0);
        jdbc.update("UPDATE os_historico SET ocorrido_em=NULL,sequencia=NULL WHERE os_id=?", unknown);
        order("2026-09-15T04:00:00Z",true,new String[]{"RECEBIDA","EM_DIAGNOSTICO"},0,120); // future
        order("2026-09-15T04:00:00Z",true,new String[]{"RECEBIDA","EM_DIAGNOSTICO"},5,5); // tie
        order("2026-09-15T04:00:00Z",false,new String[]{"EM_DIAGNOSTICO"},60); // measured zero
        UUID wrongCounter = order("2026-09-15T04:00:00Z",true,new String[]{"RECEBIDA","EM_DIAGNOSTICO"},0,5);
        jdbc.update("UPDATE ordens_servico SET sequencia_historico=3 WHERE id=?", wrongCounter);
        UUID wrongStatus = order("2026-09-15T04:00:00Z",true,new String[]{"RECEBIDA","EM_DIAGNOSTICO"},0,5);
        jdbc.update("UPDATE os_historico SET status_novo='RECEBIDA' WHERE os_id=? AND sequencia=2", wrongStatus);
        var current = reports.statusAtual(Instant.parse("2026-09-15T05:00:00Z"));
        var diagnosis = current.stream().filter(s -> s.status()==StatusOrdemServico.EM_DIAGNOSTICO).findFirst().orElseThrow();
        assertThat(diagnosis.quantidade()).isEqualTo(8);
        assertThat(diagnosis.amostrasIdade()).isEqualTo(3);
        assertThat(diagnosis.idadesDesconhecidas()).isEqualTo(5);
        assertThat(diagnosis.idadeMaximaSegundos()).isEqualByComparingTo("3300");
        assertThat(current.stream().filter(s -> s.status()==StatusOrdemServico.EM_EXECUCAO).findFirst().orElseThrow().idadeMaximaSegundos()).isNull();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(((HikariDataSource)jdbc.getDataSource()).getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test void rejectsInvalidPeriodAtPortBoundary() {
        assertThatThrownBy(() -> reports.consultar(day,day,zone)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reports.consultar(day,day.minusDays(1),zone)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void databaseEnforcesReadOnlyTwoSecondTimeoutAndReleasesConnection() {
        var settings = new java.util.ArrayList<String>();
        var observed = new org.springframework.jdbc.core.JdbcTemplate(jdbc.getDataSource()) {
            @Override public void execute(String sql) {
                super.execute(sql);
                if (sql.startsWith("SET LOCAL")) {
                    settings.add(queryForObject("SHOW transaction_read_only",String.class));
                    settings.add(queryForObject("SHOW statement_timeout",String.class));
                    queryForObject("SELECT pg_sleep(3)",Object.class);
                }
            }
        };
        var adapter = new JdbcRelatoriosAdapter(observed,transactionManager);
        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter.consultar(day,day.plusDays(1),zone))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().isInstanceOf(java.sql.SQLException.class)
                .extracting(error -> ((java.sql.SQLException)error).getSQLState()).isEqualTo("57014");
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
        assertThat(settings).containsExactly("on","2s");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(((HikariDataSource)jdbc.getDataSource()).getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(jdbc.queryForObject("SHOW statement_timeout",String.class)).isEqualTo("0");
    }

    @Test void explainRepresentativeData() throws Exception {
        // Representative synthetic data stays in this disposable Testcontainers database.
        jdbc.update("""
            INSERT INTO ordens_servico(id,cliente_id,veiculo_id,status,criado_em_utc,historico_completo_desde_inicio,sequencia_historico)
            SELECT md5('report-'||n)::uuid,?,?,'ENTREGUE',
                   CASE WHEN n<=20 THEN '2026-09-15T04:00:00Z'::timestamptz ELSE '2025-01-01T04:00:00Z'::timestamptz END,TRUE,6
            FROM generate_series(1,20000) n
            """,customer,vehicle);
        jdbc.execute("""
            INSERT INTO os_historico(os_id,status_anterior,status_novo,ator_tipo,ocorrido_em,sequencia)
            SELECT os.id,CASE WHEN n>1 THEN (ARRAY['RECEBIDA','EM_DIAGNOSTICO','AGUARDANDO_APROVACAO','EM_EXECUCAO','FINALIZADA','ENTREGUE'])[n-1]::status_os END,
                   (ARRAY['RECEBIDA','EM_DIAGNOSTICO','AGUARDANDO_APROVACAO','EM_EXECUCAO','FINALIZADA','ENTREGUE'])[n]::status_os,
                   'SYSTEM',os.criado_em_utc+(n-1)*interval '1 minute',n
            FROM ordens_servico os CROSS JOIN generate_series(1,6) n
            """);
        jdbc.execute("ANALYZE ordens_servico");
        jdbc.execute("ANALYZE os_historico");
        var named = new NamedParameterJdbcTemplate(jdbc);
        var parameters = Map.of("inicio",day.atStartOfDay(zone).toOffsetDateTime(),"fim",day.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        String sql = new ClassPathResource("queries/relatorio-periodo.sql").getContentAsString(StandardCharsets.UTF_8);
        String after = String.join("\n",named.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql,parameters,String.class));
        Files.writeString(Path.of("target/relatorio-explain.txt"),"20,000 orders / 120,000 transitions / 20 delivered in period\nV8\n"+after);
        assertThat(after).contains("idx_relatorio_entrega_em","idx_relatorio_criado_em");
        assertThat(reports.consultar(day,day.plusDays(1),zone).elegiveis()).isEqualTo(20);
    }

    void referenceFixture() {
        order("2026-09-15T04:00:00Z", true,
                new String[]{"RECEBIDA","EM_DIAGNOSTICO","AGUARDANDO_APROVACAO","EM_DIAGNOSTICO","AGUARDANDO_APROVACAO","EM_EXECUCAO","FINALIZADA","ENTREGUE"},
                0, 5, 25, 30, 45, 50, 110, 140);
        order("2026-09-15T06:00:00Z", true,
                new String[]{"RECEBIDA","EM_DIAGNOSTICO","AGUARDANDO_APROVACAO","EM_EXECUCAO","FINALIZADA","ENTREGUE"},
                0, 5, 30, 35, 75, 85);
        order("2026-09-15T04:00:00Z", true, new String[]{"RECEBIDA","EM_DIAGNOSTICO"},0,5);
        order("2026-09-15T04:00:00Z", false, new String[]{"RECEBIDA","ENTREGUE"},0,5);
    }

    UUID order(String start, boolean complete, String[] statuses, int... minutes) {
        UUID id = UUID.randomUUID();
        var created = Instant.parse(start).atOffset(ZoneOffset.UTC);
        jdbc.update("INSERT INTO ordens_servico(id,cliente_id,veiculo_id,status,criado_em_utc,historico_completo_desde_inicio,sequencia_historico) VALUES (?,?,?,?::status_os,?,?,?)",
                id, customer, vehicle, statuses[statuses.length-1], created, complete, statuses.length);
        for (int i=0;i<statuses.length;i++) {
            jdbc.update("INSERT INTO os_historico(os_id,status_anterior,status_novo,ator_tipo,ocorrido_em,sequencia) VALUES (?,?::status_os,?::status_os,'SYSTEM',?,?)",
                    id, i==0 ? null : statuses[i-1], statuses[i], created.plusMinutes(minutes[i]), i+1);
        }
        return id;
    }
}
