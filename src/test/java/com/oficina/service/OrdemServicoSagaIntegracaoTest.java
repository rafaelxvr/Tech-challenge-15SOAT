package com.oficina.service;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.dto.*;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.exception.BusinessRuleException;
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

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({OrdemServicoService.class,
        RegistroTransicaoOs.class, CicloOsSagaService.class, ExecutorEfeitos.class, RoteadorRespostas.class,
        OsDaSagaAdapter.class, JdbcSagaOutbox.class, MapeadorMensagens.class, SagaConfiguration.class,
        OrdemServicoSagaIntegracaoTest.Relogio.class})
@TestPropertySource(properties = {"oficina.historico.zona-compatibilidade=UTC", "oficina.saga.enabled=true",
        "oficina.saga.filial-padrao=MATRIZ", "oficina.saga.prazos.diagnostico=3d",
        "oficina.saga.prazos.resposta-automatica=5m", "oficina.saga.prazos.decisao=7d",
        "oficina.saga.prazos.pagamento=30m", "oficina.saga.prazos.compensacao=5m",
        "oficina.saga.prazos.max-tentativas-compensacao=5"})
class OrdemServicoSagaIntegracaoTest extends PostgresIntegrationSupport {
    @TestConfiguration static class Relogio {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC); }
    }
    @Autowired OrdemServicoService service;
    @Autowired CicloOsSagaService ciclo;
    @Autowired ClienteRepository clientes;
    @Autowired VeiculoRepository veiculos;
    @Autowired OrdemServicoRepository ordens;
    @Autowired PecaRepository pecas;
    @Autowired ServicoRepository servicos;
    @MockBean NotificacaoPort notificacoes;
    @MockBean ClienteService clienteService;
    @MockBean VeiculoService veiculoService;
    @MockBean ServicoService servicoService;
    @MockBean PecaService pecaService;
    UUID servico;
    com.oficina.entity.Cliente cliente;

    @BeforeEach void dados() {
        jdbc.update("DELETE FROM saga_outbox");
        jdbc.update("DELETE FROM os_saga_passo");
        jdbc.update("DELETE FROM os_saga");
        cliente = SagaFixtures.cliente(clientes);
        servico = SagaFixtures.servico(jdbc, "80.00");
    }

    OrdemServicoDetalheResponse abrir() {
        var veiculo = veiculos.findById(SagaFixtures.veiculo(jdbc, cliente.getId())).orElseThrow();
        org.mockito.Mockito.when(clienteService.obterEntidadeAtivaPorDocumento(cliente.getDocumento())).thenReturn(cliente);
        org.mockito.Mockito.when(veiculoService.obterAtivoPorPlaca(veiculo.getPlaca())).thenReturn(veiculo);
        org.mockito.Mockito.when(servicoService.obterAtivo(servico)).thenReturn(servicos.findById(servico).orElseThrow());
        return service.criar(new CriarOrdemServicoRequest(cliente.getDocumento(), veiculo.getPlaca(), null, null, null,
                List.of(new ItemServicoOsRequest(servico, 1, null)), List.of(), null));
    }

    IdentidadeAutenticada dono() {
        return new IdentidadeAutenticada(TipoPrincipal.CUSTOMER, cliente.getId(),
                Set.of("SCOPE_orders:read:self", "SCOPE_orders:decide:self"), 1);
    }

    void levarAteAguardandoAprovacao(UUID osId) {
        var saga = ciclo.detalhar(osId).orElseThrow();
        UUID diag = jdbc.queryForObject("SELECT message_id FROM saga_outbox WHERE os_id=? ORDER BY sequencia DESC LIMIT 1", UUID.class, osId);
        transaction.executeWithoutResult(st -> ciclo.aplicarResposta(resposta(saga.sagaId(), osId, "DiagnosticoConcluido", diag,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())));
        UUID orc = jdbc.queryForObject("SELECT message_id FROM saga_outbox WHERE os_id=? ORDER BY sequencia DESC LIMIT 1", UUID.class, osId);
        transaction.executeWithoutResult(st -> ciclo.aplicarResposta(resposta(saga.sagaId(), osId, "OrcamentoGerado", orc,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("orcamentoId", UUID.randomUUID().toString()).put("total", 80))));
    }

    static com.oficina.saga.application.mensagem.Envelope resposta(UUID sagaId, UUID osId, String tipo, UUID causa,
                                                                   com.fasterxml.jackson.databind.node.ObjectNode payload) {
        return new com.oficina.saga.application.mensagem.Envelope(UUID.randomUUID(),
                com.oficina.saga.application.mensagem.Envelope.Kind.REPLY, tipo, 1, sagaId, osId, 1, causa,
                Instant.parse("2026-09-15T12:01:00Z"), UUID.randomUUID().toString(), null, payload);
    }

    @Test void aberturaIniciaSagaEDiagnostico() {
        var criada = abrir();
        assertThat(criada.status()).isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);
        assertThat(criada.historico()).hasSize(2);
        assertThat(ciclo.conduzidaPorSaga(criada.id())).isTrue();
    }

    @Test void transicoesManuaisSaoRecusadasParaOsComSaga() {
        var criada = abrir();
        assertThatThrownBy(() -> service.iniciarDiagnostico(criada.id(), null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.enviarOrcamento(criada.id(), null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.finalizar(criada.id(), null)).isInstanceOf(BusinessRuleException.class);
    }

    @Test void segundaDecisaoRetorna422() {
        var criada = abrir();
        levarAteAguardandoAprovacao(criada.id());
        var acompanhamento = service.decidirComoCliente(criada.numero(), dono(),
                new DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO, null));
        assertThat(acompanhamento.status()).isEqualTo(StatusOrdemServico.AGUARDANDO_APROVACAO);
        assertThat(acompanhamento.estadoSaga()).isEqualTo("AGUARDANDO_DECISAO");
        assertThat(jdbc.queryForObject("SELECT tipo FROM saga_outbox WHERE os_id=? ORDER BY sequencia DESC LIMIT 1",
                String.class, criada.id())).isEqualTo("RegistrarDecisao");
        assertThatThrownBy(() -> service.decidirComoCliente(criada.numero(), dono(),
                new DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.RECUSADO, null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test void decisaoDuranteCompensacaoRetorna422() {
        var criada = abrir();
        levarAteAguardandoAprovacao(criada.id());
        jdbc.update("UPDATE os_saga SET prazo = ?::timestamptz WHERE os_id = ?", "2026-09-14T00:00:00Z", criada.id());
        assertThat(ciclo.expirarPrazosVencidos(10)).isEqualTo(1);
        assertThat(ciclo.resumo(criada.id())).get().extracting(r -> r.estado()).isEqualTo("COMPENSANDO");
        assertThat(ordens.findById(criada.id()).orElseThrow().getStatus()).isEqualTo(StatusOrdemServico.AGUARDANDO_APROVACAO);

        assertThatThrownBy(() -> service.decidirComoCliente(criada.numero(), dono(),
                new DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Orçamento não está mais aguardando decisão.");
    }

    @Test void decisaoForaDeAguardandoAprovacaoERecusada() {
        var criada = abrir();
        assertThatThrownBy(() -> service.decidirComoCliente(criada.numero(), dono(),
                new DecisaoClienteRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO, null)))
                .isInstanceOf(BusinessRuleException.class);
    }
}
