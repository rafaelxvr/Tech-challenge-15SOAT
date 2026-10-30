package com.oficina.service;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.dto.*;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.repository.*;
import com.oficina.saga.adapter.out.os.OsDaSagaAdapter;
import com.oficina.saga.adapter.out.persistence.JdbcSagaOutbox;
import com.oficina.saga.application.*;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.config.SagaConfiguration;
import com.oficina.security.IdentidadeAutenticada;
import com.oficina.security.TipoPrincipal;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Production wiring with the saga switch left at its default (off): new orders keep the Phase 3 manual flow.
 * The property is deliberately not set here, so this test also pins the default in application.yml.
 */
@Import({OrdemServicoService.class,
        RegistroTransicaoOs.class, CicloOsSagaService.class, ExecutorEfeitos.class, RoteadorRespostas.class,
        OsDaSagaAdapter.class, JdbcSagaOutbox.class, MapeadorMensagens.class, SagaConfiguration.class,
        OrdemServicoSemSagaIntegracaoTest.Relogio.class})
@TestPropertySource(properties = {"oficina.historico.zona-compatibilidade=UTC",
        "oficina.saga.filial-padrao=MATRIZ", "oficina.saga.prazos.diagnostico=3d",
        "oficina.saga.prazos.resposta-automatica=5m", "oficina.saga.prazos.decisao=7d",
        "oficina.saga.prazos.pagamento=30m", "oficina.saga.prazos.compensacao=5m",
        "oficina.saga.prazos.max-tentativas-compensacao=5"})
class OrdemServicoSemSagaIntegracaoTest extends PostgresIntegrationSupport {
    @TestConfiguration static class Relogio {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired OrdemServicoService service;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired ServicoRepository servicos;
    @MockBean NotificacaoPort notificacoes;
    @MockBean ClienteService clienteService;
    @MockBean VeiculoService veiculoService;
    @MockBean ServicoService servicoService;
    @MockBean PecaService pecaService;
    com.oficina.entity.Cliente cliente;

    @BeforeEach void dados() {
        cliente = SagaFixtures.cliente(clientes);
    }

    @Test void comASagaDesligadaAOsSegueOFluxoManualDaFase3() {
        var servico = SagaFixtures.servico(jdbc, "80.00");
        var veiculo = veiculos.findById(SagaFixtures.veiculo(jdbc, cliente.getId())).orElseThrow();
        when(clienteService.obterEntidadeAtivaPorDocumento(cliente.getDocumento())).thenReturn(cliente);
        when(veiculoService.obterAtivoPorPlaca(veiculo.getPlaca())).thenReturn(veiculo);
        when(servicoService.obterAtivo(servico)).thenReturn(servicos.findById(servico).orElseThrow());

        var criada = service.criar(new CriarOrdemServicoRequest(cliente.getDocumento(), veiculo.getPlaca(), null, null,
                null, List.of(new ItemServicoOsRequest(servico, 1, null)), List.of(), null));

        assertThat(criada.status()).isEqualTo(StatusOrdemServico.RECEBIDA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM os_saga WHERE os_id=?", Integer.class, criada.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM saga_outbox WHERE os_id=?", Integer.class, criada.id())).isZero();
        assertThat(service.iniciarDiagnostico(criada.id(), null).status()).isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);
        assertThat(service.enviarOrcamento(criada.id(), null).status()).isEqualTo(StatusOrdemServico.AGUARDANDO_APROVACAO);
        var dono = new IdentidadeAutenticada(TipoPrincipal.CUSTOMER, cliente.getId(),
                Set.of("SCOPE_orders:read:self", "SCOPE_orders:decide:self"), 1);
        var acompanhamento = service.decidirComoCliente(criada.numero(), dono,
                new DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO, null));
        assertThat(acompanhamento.status()).isEqualTo(StatusOrdemServico.EM_EXECUCAO);
        assertThat(acompanhamento.estadoSaga()).isNull();
    }
}
