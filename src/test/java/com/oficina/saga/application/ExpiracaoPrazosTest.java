package com.oficina.saga.application;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.repository.*;
import com.oficina.saga.adapter.out.os.OsDaSagaAdapter;
import com.oficina.saga.adapter.out.persistence.JdbcSagaOutbox;
import com.oficina.saga.adapter.out.persistence.OsSagaRepository;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.config.SagaConfiguration;
import com.oficina.saga.domain.EstadoSaga;
import com.oficina.saga.domain.MotivoFalha;
import com.oficina.service.RegistroTransicaoOs;
import com.oficina.support.PostgresIntegrationSupport;
import com.oficina.support.SagaFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Import({CicloOsSagaService.class, ExecutorEfeitos.class, RoteadorRespostas.class, OsDaSagaAdapter.class,
        RegistroTransicaoOs.class, JdbcSagaOutbox.class, MapeadorMensagens.class, SagaConfiguration.class,
        ExpiracaoPrazosTest.Relogio.class})
@TestPropertySource(properties = {"oficina.historico.zona-compatibilidade=UTC",
        "oficina.saga.filial-padrao=MATRIZ", "oficina.saga.prazos.diagnostico=3d",
        "oficina.saga.prazos.resposta-automatica=5m", "oficina.saga.prazos.decisao=7d",
        "oficina.saga.prazos.pagamento=30m", "oficina.saga.prazos.compensacao=5m",
        "oficina.saga.prazos.max-tentativas-compensacao=5"})
class ExpiracaoPrazosTest extends PostgresIntegrationSupport {
    static final AtomicReference<Instant> AGORA = new AtomicReference<>(Instant.parse("2026-09-15T12:00:00Z"));

    @TestConfiguration static class Relogio {
        @Bean Clock clock() {
            return new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return AGORA.get(); }
            };
        }
    }
    @Autowired CicloOsSagaService ciclo;
    @Autowired OsSagaRepository sagas;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired OrdemServicoRepository ordens;
    @Autowired PecaRepository pecas;
    @Autowired ServicoRepository servicos;
    @MockBean NotificacaoPort notificacoes;

    @BeforeEach void limpar() {
        AGORA.set(Instant.parse("2026-09-15T12:00:00Z"));
        jdbc.update("DELETE FROM saga_outbox");
        jdbc.update("DELETE FROM os_saga_passo");
        jdbc.update("DELETE FROM os_saga");
    }

    @Test void diagnosticoSemRespostaNoPrazoCancelaExecucao() {
        var cliente = SagaFixtures.cliente(clientes);
        UUID os = SagaFixtures.ordem(transaction, ordens, veiculos, pecas, servicos, cliente,
                SagaFixtures.veiculo(jdbc, cliente.getId()), StatusOrdemServico.RECEBIDA, Map.of(), Map.of());
        transaction.executeWithoutResult(st -> ciclo.iniciar(os));

        AGORA.set(AGORA.get().plus(Duration.ofDays(2)));
        assertThat(ciclo.expirarPrazosVencidos(20)).isZero();

        AGORA.set(AGORA.get().plus(Duration.ofDays(1)));
        assertThat(ciclo.expirarPrazosVencidos(20)).isEqualTo(1);
        transaction.executeWithoutResult(st -> {
            var saga = sagas.findByOsId(os).orElseThrow();
            assertThat(saga.getEstado()).isEqualTo(EstadoSaga.COMPENSANDO);
            assertThat(saga.getMotivoFalha()).isEqualTo(MotivoFalha.DIAGNOSTICO_SEM_RESPOSTA);
        });
        assertThat(jdbc.queryForObject("SELECT tipo FROM saga_outbox WHERE os_id=? ORDER BY sequencia DESC LIMIT 1",
                String.class, os)).isEqualTo("CancelarExecucao");
    }
}
