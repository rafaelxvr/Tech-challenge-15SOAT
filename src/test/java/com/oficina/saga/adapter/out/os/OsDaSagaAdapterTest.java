package com.oficina.saga.adapter.out.os;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.repository.*;
import com.oficina.saga.domain.MotivoFalha;
import com.oficina.service.RegistroTransicaoOs;
import com.oficina.support.PostgresIntegrationSupport;
import com.oficina.support.SagaFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Import({OsDaSagaAdapter.class, RegistroTransicaoOs.class, OsDaSagaAdapterTest.Relogio.class})
@TestPropertySource(properties = "oficina.historico.zona-compatibilidade=UTC")
class OsDaSagaAdapterTest extends PostgresIntegrationSupport {
    @TestConfiguration static class Relogio {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired OsDaSagaAdapter adapter;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired OrdemServicoRepository ordens;
    @Autowired PecaRepository pecas;
    @Autowired ServicoRepository servicos;
    @MockBean NotificacaoPort notificacoes;

    UUID ordem(StatusOrdemServico status, Map<UUID, Integer> pecasQtd, Map<UUID, Integer> servicosQtd) {
        var cliente = SagaFixtures.cliente(clientes);
        return SagaFixtures.ordem(transaction, ordens, veiculos, pecas, servicos, cliente,
                SagaFixtures.veiculo(jdbc, cliente.getId()), status, pecasQtd, servicosQtd);
    }

    int estoque(UUID peca) { return jdbc.queryForObject("SELECT quantidade_estoque FROM pecas WHERE id=?", Integer.class, peca); }
    String status(UUID os) { return jdbc.queryForObject("SELECT status::text FROM ordens_servico WHERE id=?", String.class, os); }

    @Test void transicoesDaSagaAtualizamOsENotificam() {
        UUID os = ordem(StatusOrdemServico.RECEBIDA, Map.of(), Map.of());
        transaction.executeWithoutResult(st -> adapter.iniciarDiagnostico(os));
        transaction.executeWithoutResult(st -> adapter.enviarOrcamento(os, new BigDecimal("123.45")));
        assertThat(status(os)).isEqualTo("AGUARDANDO_APROVACAO");
        assertThat(jdbc.queryForObject("SELECT valor_total FROM ordens_servico WHERE id=?", BigDecimal.class, os))
                .isEqualByComparingTo("123.45");
        transaction.executeWithoutResult(st -> adapter.aguardarPagamento(os));
        transaction.executeWithoutResult(st -> adapter.iniciarExecucao(os));
        transaction.executeWithoutResult(st -> adapter.finalizar(os));
        assertThat(status(os)).isEqualTo("FINALIZADA");
        verify(notificacoes, times(5)).notificarAtualizacaoStatus(any());
    }

    @Test void cancelarRegistraMotivoNoHistorico() {
        UUID os = ordem(StatusOrdemServico.AGUARDANDO_APROVACAO, Map.of(), Map.of());
        transaction.executeWithoutResult(st -> adapter.cancelar(os, MotivoFalha.DECISAO_EXPIRADA));
        assertThat(status(os)).isEqualTo("CANCELADA");
        assertThat(jdbc.queryForObject("SELECT observacao FROM os_historico WHERE os_id=? ORDER BY sequencia DESC LIMIT 1",
                String.class, os)).isEqualTo("Cancelada pela saga: DECISAO_EXPIRADA");
    }

    @Test void reservaEReposicaoDeEstoque() {
        UUID peca = SagaFixtures.peca(jdbc, 5, "10.00");
        UUID os = ordem(StatusOrdemServico.AGUARDANDO_APROVACAO, Map.of(peca, 2), Map.of());
        Boolean reservou = transaction.execute(st -> adapter.reservarPecas(os));
        assertThat(reservou).isTrue();
        assertThat(estoque(peca)).isEqualTo(3);
        transaction.executeWithoutResult(st -> adapter.reporPecas(os));
        assertThat(estoque(peca)).isEqualTo(5);
    }

    @Test void reservaSomaLinhasDaMesmaPecaENaoBaixaParcialmente() {
        UUID suficiente = SagaFixtures.peca(jdbc, 10, "10.00");
        UUID curta = SagaFixtures.peca(jdbc, 3, "10.00");
        UUID os = ordem(StatusOrdemServico.AGUARDANDO_APROVACAO, Map.of(suficiente, 4, curta, 2), Map.of());
        transaction.executeWithoutResult(st -> ordens.findById(os).orElseThrow().adicionarPeca(
                com.oficina.entity.OsPecaItem.builder().peca(pecas.findById(curta).orElseThrow()).quantidade(2)
                        .valorUnitario(new BigDecimal("10.00")).valorTotal(new BigDecimal("20.00")).build()));
        Boolean reservou = transaction.execute(st -> adapter.reservarPecas(os));
        assertThat(reservou).isFalse();
        assertThat(estoque(suficiente)).isEqualTo(10);
        assertThat(estoque(curta)).isEqualTo(3);
    }

    @Test void snapshotTrazLinhasPrecificadasSemDadosDeContato() {
        UUID peca = SagaFixtures.peca(jdbc, 5, "35.50");
        UUID servico = SagaFixtures.servico(jdbc, "80.00");
        UUID os = ordem(StatusOrdemServico.EM_DIAGNOSTICO, Map.of(peca, 2), Map.of(servico, 1));
        var snapshot = transaction.execute(st -> adapter.snapshot(os));
        assertThat(snapshot.numero()).isPositive();
        assertThat(snapshot.itens()).extracting(i -> i.tipo() + ":" + i.quantidade() + ":" + i.precoUnitario())
                .containsExactlyInAnyOrder("SERVICO:1:80.00", "PECA:2:35.50");
    }
}
