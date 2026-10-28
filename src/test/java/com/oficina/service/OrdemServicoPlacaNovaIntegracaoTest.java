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
 * Regressão UNR-279: placa ainda não cadastrada cria o veículo e a OS na mesma transação (PostgreSQL real, sem mock
 * de VeiculoService). Antes da correção o lookup da placa marcava a transação externa como rollback-only.
 */
@Import({OrdemServicoService.class, VeiculoService.class,
        RegistroTransicaoOs.class, CicloOsSagaService.class, ExecutorEfeitos.class, RoteadorRespostas.class,
        OsDaSagaAdapter.class, JdbcSagaOutbox.class, MapeadorMensagens.class, SagaConfiguration.class,
        OrdemServicoPlacaNovaIntegracaoTest.Relogio.class})
@TestPropertySource(properties = {"oficina.historico.zona-compatibilidade=UTC",
        "oficina.saga.filial-padrao=MATRIZ", "oficina.saga.prazos.diagnostico=3d",
        "oficina.saga.prazos.resposta-automatica=5m", "oficina.saga.prazos.decisao=7d",
        "oficina.saga.prazos.pagamento=30m", "oficina.saga.prazos.compensacao=5m",
        "oficina.saga.prazos.max-tentativas-compensacao=5"})
class OrdemServicoPlacaNovaIntegracaoTest extends PostgresIntegrationSupport {
    @TestConfiguration static class Relogio {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired OrdemServicoService service;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired ServicoRepository servicos;
    @Autowired OrdemServicoRepository ordens;
    @MockBean NotificacaoPort notificacoes;
    @MockBean ClienteService clienteService;
    @MockBean ServicoService servicoService;
    @MockBean PecaService pecaService;
    com.oficina.entity.Cliente cliente;

    @BeforeEach void dados() {
        cliente = SagaFixtures.cliente(clientes);
    }

    @Test void placaNovaCadastraOVeiculoAutomaticamenteECriaAOs() {
        var servico = SagaFixtures.servico(jdbc, "80.00");
        when(clienteService.obterEntidadeAtivaPorDocumento(cliente.getDocumento())).thenReturn(cliente);
        when(servicoService.obterAtivo(servico)).thenReturn(servicos.findById(servico).orElseThrow());
        assertThat(veiculos.findByPlaca("TAB1C23")).isEmpty();

        var criada = service.criar(new CriarOrdemServicoRequest(cliente.getDocumento(), "TAB1C23", "Fiat", "Uno", 2020,
                List.of(new ItemServicoOsRequest(servico, 1, null)), List.of(), null));

        assertThat(criada.status()).isEqualTo(StatusOrdemServico.RECEBIDA);
        var veiculo = veiculos.findByPlaca("TAB1C23").orElseThrow();
        assertThat(veiculo.getCliente().getId()).isEqualTo(cliente.getId());
        assertThat(ordens.findById(criada.id())).isPresent();
    }
}
