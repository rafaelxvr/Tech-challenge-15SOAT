package com.oficina.saga.application;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.exception.BusinessRuleException;
import com.oficina.repository.*;
import com.oficina.saga.adapter.out.os.OsDaSagaAdapter;
import com.oficina.saga.adapter.out.persistence.JdbcSagaOutbox;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.config.SagaConfiguration;
import com.oficina.service.RegistroTransicaoOs;
import com.oficina.support.PostgresIntegrationSupport;
import com.oficina.support.SagaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.*;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({CicloOsSagaService.class, ExecutorEfeitos.class, RoteadorRespostas.class, OsDaSagaAdapter.class,
        RegistroTransicaoOs.class, JdbcSagaOutbox.class, MapeadorMensagens.class, SagaConfiguration.class,
        CicloOsSagaServiceTest.Relogio.class})
@TestPropertySource(properties = {"oficina.historico.zona-compatibilidade=UTC",
        "oficina.saga.filial-padrao=MATRIZ", "oficina.saga.prazos.diagnostico=3d",
        "oficina.saga.prazos.resposta-automatica=5m", "oficina.saga.prazos.decisao=7d",
        "oficina.saga.prazos.pagamento=30m", "oficina.saga.prazos.compensacao=5m",
        "oficina.saga.prazos.max-tentativas-compensacao=5"})
class CicloOsSagaServiceTest extends PostgresIntegrationSupport {
    @TestConfiguration static class Relogio {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired CicloOsSagaService ciclo;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired OrdemServicoRepository ordens;
    @Autowired PecaRepository pecas;
    @Autowired ServicoRepository servicos;
    @MockBean NotificacaoPort notificacoes;
    @SpyBean OsDaSagaAdapter osDaSaga;

    @BeforeEach void limpar() {
        jdbc.update("DELETE FROM saga_outbox");
        jdbc.update("DELETE FROM os_saga_passo");
        jdbc.update("DELETE FROM os_saga");
    }

    UUID ordemRecebida() {
        var cliente = SagaFixtures.cliente(clientes);
        return SagaFixtures.ordem(transaction, ordens, veiculos, pecas, servicos, cliente,
                SagaFixtures.veiculo(jdbc, cliente.getId()), StatusOrdemServico.RECEBIDA,
                Map.of(), Map.of(SagaFixtures.servico(jdbc, "80.00"), 1));
    }

    @Test void iniciarAbreDiagnosticoEGravaComandoNoOutbox() {
        UUID os = ordemRecebida();
        transaction.executeWithoutResult(st -> ciclo.iniciar(os));
        assertThat(jdbc.queryForObject("SELECT status::text FROM ordens_servico WHERE id=?", String.class, os))
                .isEqualTo("EM_DIAGNOSTICO");
        assertThat(jdbc.queryForMap("SELECT destino, tipo, envelope->>'kind' AS kind FROM saga_outbox WHERE os_id=?", os))
                .containsEntry("destino", "EXECUCAO").containsEntry("tipo", "IniciarDiagnostico")
                .containsEntry("kind", "COMMAND");
        assertThat(ciclo.conduzidaPorSaga(os)).isTrue();
        assertThat(ciclo.resumo(os)).get().extracting(r -> r.estado()).isEqualTo("AGUARDANDO_DIAGNOSTICO");
    }

    @Test void iniciarDuasVezesERecusado() {
        UUID os = ordemRecebida();
        transaction.executeWithoutResult(st -> ciclo.iniciar(os));
        assertThatThrownBy(() -> transaction.executeWithoutResult(st -> ciclo.iniciar(os)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test void semTransacaoIniciarFalha() {
        UUID os = ordemRecebida();
        assertThatThrownBy(() -> ciclo.iniciar(os))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void decisaoComSagaForaDeAguardandoDecisaoERegraDeNegocio() {
        UUID os = ordemRecebida();
        transaction.executeWithoutResult(st -> ciclo.iniciar(os));
        assertThatThrownBy(() -> transaction.executeWithoutResult(st -> ciclo.registrarDecisao(os, true)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Orçamento não está mais aguardando decisão.");
        assertThat(ciclo.resumo(os)).get().extracting(r -> r.estado()).isEqualTo("AGUARDANDO_DIAGNOSTICO");
    }

    @Test void detalharExpoeTrilha() {
        UUID os = ordemRecebida();
        transaction.executeWithoutResult(st -> ciclo.iniciar(os));
        var detalhe = ciclo.detalhar(os).orElseThrow();
        assertThat(detalhe.estado()).isEqualTo("AGUARDANDO_DIAGNOSTICO");
        assertThat(detalhe.passos()).extracting(SagaDetalhe.Passo::evento)
                .containsExactly("ESTADO:AGUARDANDO_DIAGNOSTICO", "COMANDO:IniciarDiagnostico");
        assertThat(detalhe.compensacoesPendentes()).containsExactly("CANCELAR_EXECUCAO");
    }

    @Test void cadaSagaExpiradaTemUmaUnicaCorrelacaoNoMdc() {
        UUID primeira = ordemRecebida();
        UUID segunda = ordemRecebida();
        transaction.executeWithoutResult(st -> { ciclo.iniciar(primeira); ciclo.iniciar(segunda); });
        jdbc.update("DELETE FROM saga_outbox");
        jdbc.update("UPDATE os_saga SET prazo = ?::timestamptz", "2026-09-14T00:00:00Z");
        Map<UUID, String> correlacoes = new java.util.concurrent.ConcurrentHashMap<>();
        transaction.executeWithoutResult(st -> org.mockito.Mockito.doAnswer(chamada -> {
            correlacoes.put(chamada.getArgument(0), org.slf4j.MDC.get(com.oficina.config.CorrelationFilter.CORRELATION_ID));
            return chamada.callRealMethod();
        }).when(osDaSaga).snapshot(org.mockito.ArgumentMatchers.any()));

        assertThat(ciclo.expirarPrazosVencidos(10)).isEqualTo(2);

        for (UUID os : java.util.List.of(primeira, segunda)) {
            assertThat(correlacoes.get(os)).as("MDC correlation while %s expires", os)
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            assertThat(jdbc.queryForObject("SELECT envelope->>'correlationId' FROM saga_outbox WHERE os_id=?",
                    String.class, os)).isEqualTo(correlacoes.get(os));
        }
        assertThat(correlacoes.get(primeira)).isNotEqualTo(correlacoes.get(segunda));
        assertThat(org.slf4j.MDC.get(com.oficina.config.CorrelationFilter.CORRELATION_ID)).isNull();
    }

    @Test void sagaComFalhaNaoImpedeAsDemaisDeExpirar() {
        UUID falha = ordemRecebida();
        UUID saudavel = ordemRecebida();
        transaction.executeWithoutResult(st -> { ciclo.iniciar(falha); ciclo.iniciar(saudavel); });
        jdbc.update("DELETE FROM saga_outbox");
        jdbc.update("UPDATE os_saga SET prazo = ?::timestamptz WHERE os_id = ?", "2026-09-14T00:00:00Z", falha);
        jdbc.update("UPDATE os_saga SET prazo = ?::timestamptz WHERE os_id = ?", "2026-09-14T01:00:00Z", saudavel);
        transaction.executeWithoutResult(st -> org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(osDaSaga).snapshot(falha));

        assertThat(ciclo.expirarPrazosVencidos(10)).isEqualTo(1);

        assertThat(ciclo.resumo(saudavel)).get().extracting(r -> r.estado()).isEqualTo("COMPENSANDO");
        assertThat(ciclo.resumo(falha)).get().extracting(r -> r.estado()).isEqualTo("AGUARDANDO_DIAGNOSTICO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_outbox WHERE os_id=?", Integer.class, saudavel))
                .isEqualTo(1);
    }
}
