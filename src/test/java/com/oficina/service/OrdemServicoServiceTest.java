package com.oficina.service;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.dto.*;
import com.oficina.entity.*;
import com.oficina.exception.BusinessRuleException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.repository.OrdemServicoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrdemServicoServiceTest {

    private static final String CPF = "39053344705";

    @Mock
    private OrdemServicoRepository ordemServicoRepository;

    @Mock
    private ClienteService clienteService;

    @Mock
    private VeiculoService veiculoService;

    @Mock
    private ServicoService servicoService;

    @Mock
    private PecaService pecaService;

    @Mock
    private NotificacaoPort notificacaoPort;

    @org.mockito.Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC);

    @InjectMocks
    private OrdemServicoService ordemServicoService;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @org.junit.jupiter.api.BeforeEach
    void setToken() {
        ReflectionTestUtils.setField(ordemServicoService, "zonaCompatibilidade", "UTC");
        ReflectionTestUtils.setField(ordemServicoService, "emailStatusToken", "oficina-email-status-token");
    }

    @Test
    void criar_comServico_calculaValorTotal() {
        UUID clienteId = UUID.randomUUID();
        Cliente cliente = cliente(clienteId);
        Veiculo veiculo = veiculo(cliente);
        UUID sid = UUID.randomUUID();
        Servico servico = Servico.builder()
                .id(sid)
                .nome("Serviço")
                .valor(new BigDecimal("50.00"))
                .tempoEstimadoMin(60)
                .ativo(true)
                .build();

        when(clienteService.obterEntidadeAtivaPorDocumento(any())).thenReturn(cliente);
        when(veiculoService.obterAtivoPorPlaca(any())).thenReturn(veiculo);
        when(servicoService.obterAtivo(sid)).thenReturn(servico);

        final OrdemServico[] holder = new OrdemServico[1];
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> {
            OrdemServico os = inv.getArgument(0);
            os.setId(UUID.randomUUID());
            holder[0] = os;
            return os;
        });
        when(ordemServicoRepository.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(holder[0]));

        CriarOrdemServicoRequest req = new CriarOrdemServicoRequest(
                CPF,
                "ABC1D23",
                null,
                null,
                null,
                List.of(new ItemServicoOsRequest(sid, 2, null)),
                List.of(),
                null
        );

        OrdemServicoDetalheResponse resp = ordemServicoService.criar(req);

        assertThat(resp.valorTotal()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(resp.historico()).hasSize(1);
        assertThat(resp.historico().get(0).ocorridoEm()).isEqualTo(Instant.parse("2026-09-15T12:00:00Z"));
        assertThat(resp.historico().get(0).criadoEm()).isEqualTo(LocalDateTime.of(2026, 9, 15, 12, 0));
        assertThat(holder[0].getCriadoEmUtc()).isEqualTo(resp.historico().get(0).ocorridoEm());
        assertThat(holder[0].getSequenciaHistorico()).isEqualTo(1);
        verify(clock).instant();
        verify(ordemServicoRepository).flush();
    }

    @Test
    void listar_semStatus_usaFindAtivasOrdenadas() {
        Pageable p = PageRequest.of(0, 5);
        OrdemServico os = osBasica();
        when(ordemServicoRepository.findAtivasOrdenadasPorPrioridade(isNull(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(os)));

        assertThat(ordemServicoService.listar(null, p).getContent()).hasSize(1);
    }

    @Test
    void listar_comStatus_filtra() {
        Pageable p = PageRequest.of(0, 5);
        OrdemServico os = osBasica();
        when(ordemServicoRepository.findAtivasOrdenadasPorPrioridade(
                eq(StatusOrdemServico.RECEBIDA), any(), any()))
                .thenReturn(new PageImpl<>(List.of(os)));

        assertThat(ordemServicoService.listar(StatusOrdemServico.RECEBIDA, p).getContent()).hasSize(1);
    }

    @Test
    void listar_statusFinalizada_lanca() {
        assertThatThrownBy(() -> ordemServicoService.listar(StatusOrdemServico.FINALIZADA, PageRequest.of(0, 5)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void buscarPorId_encontrado() {
        OrdemServico os = osBasica();
        when(ordemServicoRepository.findById(os.getId())).thenReturn(Optional.of(os));

        OrdemServicoDetalheResponse r = ordemServicoService.buscarPorId(os.getId());

        assertThat(r.id()).isEqualTo(os.getId());
    }

    @Test
    void acompanhamentoPublico_montaHistorico() {
        Cliente c = cliente(UUID.randomUUID());
        Veiculo v = veiculo(c);
        OrdemServico os = osBasica();
        os.setCliente(c);
        os.setVeiculo(v);
        os.setNumero(55L);
        OsHistorico h = OsHistorico.builder()
                .statusAnterior(null)
                .statusNovo(StatusOrdemServico.RECEBIDA)
                .observacao("abertura")
                .criadoEm(LocalDateTime.now().minusHours(1))
                .build();
        os.getHistorico().add(h);

        when(ordemServicoRepository.findDetalheAcompanhamentoPorNumero(55L)).thenReturn(Optional.of(os));

        AcompanhamentoOsResponse r = ordemServicoService.acompanhamentoPublico(55L);

        assertThat(r.numero()).isEqualTo(55L);
        assertThat(r.historico()).hasSize(1);
    }

    @Test
    void iniciarDiagnostico_avancaStatus() {
        OrdemServico os = osBasica();
        os.setStatus(StatusOrdemServico.RECEBIDA);
        when(ordemServicoRepository.findById(os.getId())).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.iniciarDiagnostico(os.getId(), new AcaoOrdemRequest("ok"));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);
    }

    @Test
    void aprovarPeloCliente_documentoConfere_semPecas() {
        Cliente cli = cliente(UUID.randomUUID());
        Veiculo v = veiculo(cli);
        OrdemServico os = osBasica();
        os.setCliente(cli);
        os.setVeiculo(v);
        os.setStatus(StatusOrdemServico.AGUARDANDO_APROVACAO);
        os.setNumero(100L);

        when(ordemServicoRepository.findComPecasEClientePorNumero(100L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        Usuario usuario = new Usuario();
        usuario.setId(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, List.of()));

        OrdemServicoDetalheResponse r = ordemServicoService.aprovarPeloCliente(
                100L,
                new AprovacaoClienteRequest(CPF)
        );

        assertThat(r.status()).isEqualTo(StatusOrdemServico.EM_EXECUCAO);
    }

    @Test
    void aprovarPeloCliente_documentoDiverge_lanca() {
        Cliente cli = cliente(UUID.randomUUID());
        cli.atualizarIdentidade(new com.oficina.domain.identidade.DadosIdentidadeCliente(
                cli.getTipoDocumento(), "52998224725", cli.getEmail(), cli.isAtivo()));
        OrdemServico os = osBasica();
        os.setCliente(cli);
        os.setVeiculo(veiculo(cli));
        os.setStatus(StatusOrdemServico.AGUARDANDO_APROVACAO);

        when(ordemServicoRepository.findComPecasEClientePorNumero(1L)).thenReturn(Optional.of(os));

        AprovacaoClienteRequest aprovacao = new AprovacaoClienteRequest(CPF);
        assertThatThrownBy(() -> ordemServicoService.aprovarPeloCliente(1L, aprovacao))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void criar_cadastraVeiculoQuandoPlacaNaoExiste() {
        UUID clienteId = UUID.randomUUID();
        Cliente cliente = cliente(clienteId);
        UUID sid = UUID.randomUUID();
        Servico servico = Servico.builder()
                .id(sid)
                .nome("S")
                .valor(BigDecimal.TEN)
                .tempoEstimadoMin(30)
                .ativo(true)
                .build();
        Veiculo novoVeiculo = veiculo(cliente);

        when(clienteService.obterEntidadeAtivaPorDocumento(any())).thenReturn(cliente);
        AtomicInteger chamadasPlaca = new AtomicInteger();
        when(veiculoService.obterAtivoPorPlaca(any())).thenAnswer(inv ->
                respostaObterAtivoPorPlaca(chamadasPlaca, novoVeiculo));
        when(veiculoService.criar(any())).thenReturn(
                new VeiculoResponse(novoVeiculo.getId(), "ABC1D23", "Fiat", "Uno", 2018, null, null, clienteId, true));
        when(servicoService.obterAtivo(sid)).thenReturn(servico);

        final OrdemServico[] holder = new OrdemServico[1];
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> {
            OrdemServico os = inv.getArgument(0);
            os.setId(UUID.randomUUID());
            holder[0] = os;
            return os;
        });
        when(ordemServicoRepository.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(holder[0]));

        CriarOrdemServicoRequest req = new CriarOrdemServicoRequest(
                CPF,
                "ABC1D23",
                "Fiat",
                "Uno",
                2018,
                List.of(new ItemServicoOsRequest(sid, 1, null)),
                List.of(),
                null
        );

        OrdemServicoDetalheResponse r = ordemServicoService.criar(req);

        assertThat(r.valorTotal()).isEqualByComparingTo(BigDecimal.TEN);
        verify(veiculoService).criar(any());
    }

    @Test
    void criar_semDadosVeiculoQuandoPlacaNaoExiste_lanca() {
        Cliente cliente = cliente(UUID.randomUUID());
        UUID sid = UUID.randomUUID();

        when(clienteService.obterEntidadeAtivaPorDocumento(any())).thenReturn(cliente);
        when(veiculoService.obterAtivoPorPlaca(any())).thenThrow(new EntityNotFoundException("v", "x"));

        CriarOrdemServicoRequest req = new CriarOrdemServicoRequest(
                CPF,
                "ABC1D23",
                null,
                null,
                null,
                List.of(new ItemServicoOsRequest(sid, 1, null)),
                List.of(),
                null
        );

        assertThatThrownBy(() -> ordemServicoService.criar(req)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void enviarOrcamento_avancaParaAguardandoAprovacao() {
        OrdemServico os = osBasica();
        os.setStatus(StatusOrdemServico.EM_DIAGNOSTICO);
        when(ordemServicoRepository.findById(os.getId())).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.enviarOrcamento(os.getId(), null);

        assertThat(r.status()).isEqualTo(StatusOrdemServico.AGUARDANDO_APROVACAO);
    }

    @Test
    void finalizar_deEmExecucaoParaFinalizada() {
        OrdemServico os = osBasica();
        os.setStatus(StatusOrdemServico.EM_EXECUCAO);
        when(ordemServicoRepository.findById(os.getId())).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.finalizar(os.getId(), new AcaoOrdemRequest("ok"));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.FINALIZADA);
    }

    @Test
    void registrarEntrega_deFinalizadaParaEntregue() {
        OrdemServico os = osBasica();
        os.setStatus(StatusOrdemServico.FINALIZADA);
        when(ordemServicoRepository.findById(os.getId())).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.registrarEntrega(os.getId(), null);

        assertThat(r.status()).isEqualTo(StatusOrdemServico.ENTREGUE);
    }

    @Test
    void aprovarPeloCliente_baixaEstoqueQuandoHaPecas() {
        Cliente cli = cliente(UUID.randomUUID());
        Veiculo v = veiculo(cli);
        OrdemServico os = osBasica();
        os.setCliente(cli);
        os.setVeiculo(v);
        os.setStatus(StatusOrdemServico.AGUARDANDO_APROVACAO);
        os.setNumero(200L);

        Peca peca = Peca.builder()
                .id(UUID.randomUUID())
                .codigo("PX")
                .nome("Peça")
                .valorUnitario(BigDecimal.ONE)
                .quantidadeEstoque(10)
                .quantidadeMinima(1)
                .unidadeMedida("UN")
                .ativo(true)
                .build();
        OsPecaItem linha = OsPecaItem.builder()
                .peca(peca)
                .quantidade(3)
                .valorUnitario(BigDecimal.ONE)
                .valorTotal(new BigDecimal("3"))
                .build();
        os.getPecas().add(linha);

        when(ordemServicoRepository.findComPecasEClientePorNumero(200L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        ordemServicoService.aprovarPeloCliente(200L, new AprovacaoClienteRequest(CPF));

        assertThat(peca.getQuantidadeEstoque()).isEqualTo(7);
        assertThat(os.getStatus()).isEqualTo(StatusOrdemServico.EM_EXECUCAO);
    }

    @Test
    void aprovarPeloCliente_estoqueInsuficiente_lanca() {
        Cliente cli = cliente(UUID.randomUUID());
        OrdemServico os = osBasica();
        os.setCliente(cli);
        os.setVeiculo(veiculo(cli));
        os.setStatus(StatusOrdemServico.AGUARDANDO_APROVACAO);
        os.setNumero(201L);

        Peca peca = Peca.builder()
                .codigo("PX")
                .nome("Peça")
                .valorUnitario(BigDecimal.ONE)
                .quantidadeEstoque(1)
                .quantidadeMinima(1)
                .unidadeMedida("UN")
                .ativo(true)
                .build();
        OsPecaItem linha = OsPecaItem.builder()
                .peca(peca)
                .quantidade(5)
                .valorUnitario(BigDecimal.ONE)
                .valorTotal(new BigDecimal("5"))
                .build();
        os.getPecas().add(linha);

        when(ordemServicoRepository.findComPecasEClientePorNumero(201L)).thenReturn(Optional.of(os));

        AprovacaoClienteRequest aprovacao = new AprovacaoClienteRequest(CPF);
        assertThatThrownBy(() -> ordemServicoService.aprovarPeloCliente(201L, aprovacao))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void buscarPorId_naoEncontrado_lanca() {
        UUID id = UUID.randomUUID();
        when(ordemServicoRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ordemServicoService.buscarPorId(id)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void buscarPorId_montaLinhasServicoPecaEHistorico() {
        Cliente cli = cliente(UUID.randomUUID());
        Veiculo v = veiculo(cli);
        OrdemServico os = osBasica();
        os.setCliente(cli);
        os.setVeiculo(v);
        os.setNumero(99L);
        os.setObservacoes("obs");
        Servico srv = Servico.builder()
                .id(UUID.randomUUID())
                .nome("Troca")
                .valor(BigDecimal.ONE)
                .tempoEstimadoMin(30)
                .ativo(true)
                .build();
        OsServicoItem linhaS = OsServicoItem.builder()
                .servico(srv)
                .quantidade(2)
                .valorUnitario(BigDecimal.TEN)
                .valorTotal(new BigDecimal("20"))
                .observacao("x")
                .build();
        os.getServicos().add(linhaS);
        Peca peca = Peca.builder()
                .id(UUID.randomUUID())
                .codigo("C1")
                .nome("Peça")
                .valorUnitario(BigDecimal.ONE)
                .quantidadeEstoque(5)
                .quantidadeMinima(1)
                .unidadeMedida("UN")
                .ativo(true)
                .build();
        OsPecaItem linhaP = OsPecaItem.builder()
                .peca(peca)
                .quantidade(1)
                .valorUnitario(BigDecimal.ONE)
                .valorTotal(BigDecimal.ONE)
                .build();
        os.getPecas().add(linhaP);
        os.getHistorico().add(OsHistorico.builder()
                .statusAnterior(StatusOrdemServico.RECEBIDA)
                .statusNovo(StatusOrdemServico.EM_DIAGNOSTICO)
                .observacao("h")
                .criadoEm(LocalDateTime.now().minusMinutes(5))
                .build());

        when(ordemServicoRepository.findById(os.getId())).thenReturn(Optional.of(os));

        OrdemServicoDetalheResponse r = ordemServicoService.buscarPorId(os.getId());

        assertThat(r.servicos()).hasSize(1);
        assertThat(r.pecas()).hasSize(1);
        assertThat(r.historico()).hasSize(1);
        assertThat(r.cliente().nome()).isEqualTo("Cliente");
    }

    @Test
    void criar_comPeca_noOrcamento() {
        UUID clienteId = UUID.randomUUID();
        Cliente cliente = cliente(clienteId);
        Veiculo veiculo = veiculo(cliente);
        UUID sid = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        Servico servico = Servico.builder()
                .id(sid)
                .nome("S")
                .valor(new BigDecimal("20.00"))
                .tempoEstimadoMin(30)
                .ativo(true)
                .build();
        Peca peca = Peca.builder()
                .id(pid)
                .codigo("P1")
                .nome("Peça")
                .valorUnitario(new BigDecimal("15.00"))
                .quantidadeEstoque(10)
                .quantidadeMinima(1)
                .unidadeMedida("UN")
                .ativo(true)
                .build();

        when(clienteService.obterEntidadeAtivaPorDocumento(any())).thenReturn(cliente);
        when(veiculoService.obterAtivoPorPlaca(any())).thenReturn(veiculo);
        when(servicoService.obterAtivo(sid)).thenReturn(servico);
        when(pecaService.obterAtiva(pid)).thenReturn(peca);

        final OrdemServico[] holder = new OrdemServico[1];
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> {
            OrdemServico o = inv.getArgument(0);
            o.setId(UUID.randomUUID());
            holder[0] = o;
            return o;
        });
        when(ordemServicoRepository.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(holder[0]));

        CriarOrdemServicoRequest req = new CriarOrdemServicoRequest(
                CPF,
                "ABC1D23",
                null,
                null,
                null,
                List.of(new ItemServicoOsRequest(sid, 1, null)),
                List.of(new ItemPecaOsRequest(pid, 2)),
                "obs"
        );

        OrdemServicoDetalheResponse r = ordemServicoService.criar(req);

        assertThat(r.valorTotal()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void criar_placaOutroCliente_lanca() {
        UUID clienteId = UUID.randomUUID();
        Cliente cliente = cliente(clienteId);
        UUID outroCliente = UUID.randomUUID();
        Cliente donoVeiculo = cliente(outroCliente);
        donoVeiculo.atualizarIdentidade(new com.oficina.domain.identidade.DadosIdentidadeCliente(
                donoVeiculo.getTipoDocumento(), "52998224725", donoVeiculo.getEmail(), donoVeiculo.isAtivo()));
        Veiculo veiculo = veiculo(donoVeiculo);

        UUID sid = UUID.randomUUID();
        Servico servico = Servico.builder()
                .id(sid)
                .nome("S")
                .valor(BigDecimal.ONE)
                .tempoEstimadoMin(30)
                .ativo(true)
                .build();

        when(clienteService.obterEntidadeAtivaPorDocumento(any())).thenReturn(cliente);
        when(veiculoService.obterAtivoPorPlaca(any())).thenReturn(veiculo);

        CriarOrdemServicoRequest req = new CriarOrdemServicoRequest(
                CPF,
                "ABC1D23",
                null,
                null,
                null,
                List.of(new ItemServicoOsRequest(sid, 1, null)),
                List.of(),
                null
        );

        assertThatThrownBy(() -> ordemServicoService.criar(req)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void processarDecisaoOrcamento_aprovado() {
        Cliente cli = cliente(UUID.randomUUID());
        OrdemServico os = osBasica();
        os.setCliente(cli);
        os.setVeiculo(veiculo(cli));
        os.setStatus(StatusOrdemServico.AGUARDANDO_APROVACAO);
        os.setNumero(301L);
        when(ordemServicoRepository.findComPecasEClientePorNumero(301L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.processarDecisaoOrcamento(
                301L,
                new DecisaoOrcamentoRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO, CPF, null));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.EM_EXECUCAO);
    }

    @Test
    void processarDecisaoOrcamento_recusado() {
        Cliente cli = cliente(UUID.randomUUID());
        OrdemServico os = osBasica();
        os.setCliente(cli);
        os.setVeiculo(veiculo(cli));
        os.setStatus(StatusOrdemServico.AGUARDANDO_APROVACAO);
        os.setNumero(302L);
        when(ordemServicoRepository.findComPecasEClientePorNumero(302L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.processarDecisaoOrcamento(
                302L,
                new DecisaoOrcamentoRequest(
                        DecisaoOrcamentoRequest.DecisaoOrcamento.RECUSADO, CPF, "Não autorizo"));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);
    }

    @Test
    void recusarPeloCliente_semObservacao_usaPadrao() {
        Cliente cli = cliente(UUID.randomUUID());
        OrdemServico os = osBasica();
        os.setCliente(cli);
        os.setVeiculo(veiculo(cli));
        os.setStatus(StatusOrdemServico.AGUARDANDO_APROVACAO);
        os.setNumero(303L);
        when(ordemServicoRepository.findComPecasEClientePorNumero(303L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.recusarPeloCliente(
                303L,
                new DecisaoOrcamentoRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.RECUSADO, CPF, null));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);
    }

    @Test
    void atualizarStatusViaEmail_tokenInvalido_lanca() {
        var req = new AtualizacaoStatusEmailRequest(
                1L, StatusOrdemServico.EM_DIAGNOSTICO, "token-errado", null);
        assertThatThrownBy(() -> ordemServicoService.atualizarStatusViaEmail(req))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void atualizarStatusViaEmail_iniciarDiagnostico() {
        OrdemServico os = osBasica();
        os.setNumero(401L);
        os.setStatus(StatusOrdemServico.RECEBIDA);
        when(ordemServicoRepository.findByNumero(401L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.atualizarStatusViaEmail(
                new AtualizacaoStatusEmailRequest(
                        401L, StatusOrdemServico.EM_DIAGNOSTICO, "oficina-email-status-token", "via email"));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);
    }

    @Test
    void atualizarStatusViaEmail_enviarOrcamento() {
        OrdemServico os = osBasica();
        os.setNumero(402L);
        os.setStatus(StatusOrdemServico.EM_DIAGNOSTICO);
        when(ordemServicoRepository.findByNumero(402L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.atualizarStatusViaEmail(
                new AtualizacaoStatusEmailRequest(
                        402L, StatusOrdemServico.AGUARDANDO_APROVACAO, "oficina-email-status-token", null));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.AGUARDANDO_APROVACAO);
    }

    @Test
    void atualizarStatusViaEmail_aprovarExecucao() {
        Cliente cli = cliente(UUID.randomUUID());
        OrdemServico os = osBasica();
        os.setCliente(cli);
        os.setVeiculo(veiculo(cli));
        os.setNumero(403L);
        os.setStatus(StatusOrdemServico.AGUARDANDO_APROVACAO);
        when(ordemServicoRepository.findComPecasEClientePorNumero(403L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.atualizarStatusViaEmail(
                new AtualizacaoStatusEmailRequest(
                        403L, StatusOrdemServico.EM_EXECUCAO, "oficina-email-status-token", "aprovado email"));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.EM_EXECUCAO);
    }

    @Test
    void atualizarStatusViaEmail_finalizar() {
        OrdemServico os = osBasica();
        os.setNumero(404L);
        os.setStatus(StatusOrdemServico.EM_EXECUCAO);
        when(ordemServicoRepository.findByNumero(404L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.atualizarStatusViaEmail(
                new AtualizacaoStatusEmailRequest(
                        404L, StatusOrdemServico.FINALIZADA, "oficina-email-status-token", null));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.FINALIZADA);
    }

    @Test
    void atualizarStatusViaEmail_entregar() {
        OrdemServico os = osBasica();
        os.setNumero(405L);
        os.setStatus(StatusOrdemServico.FINALIZADA);
        when(ordemServicoRepository.findByNumero(405L)).thenReturn(Optional.of(os));
        when(ordemServicoRepository.save(any(OrdemServico.class))).thenAnswer(inv -> inv.getArgument(0));

        OrdemServicoDetalheResponse r = ordemServicoService.atualizarStatusViaEmail(
                new AtualizacaoStatusEmailRequest(
                        405L, StatusOrdemServico.ENTREGUE, "oficina-email-status-token", "ok"));

        assertThat(r.status()).isEqualTo(StatusOrdemServico.ENTREGUE);
    }

    @Test
    void atualizarStatusViaEmail_recebida_lanca() {
        OrdemServico os = osBasica();
        os.setNumero(406L);
        when(ordemServicoRepository.findByNumero(406L)).thenReturn(Optional.of(os));

        assertThatThrownBy(() -> ordemServicoService.atualizarStatusViaEmail(
                new AtualizacaoStatusEmailRequest(
                        406L, StatusOrdemServico.RECEBIDA, "oficina-email-status-token", null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void listar_statusEntregue_lanca() {
        assertThatThrownBy(() -> ordemServicoService.listar(StatusOrdemServico.ENTREGUE, PageRequest.of(0, 5)))
                .isInstanceOf(BusinessRuleException.class);
    }

    private static Cliente cliente(UUID id) {
        return Cliente.builder()
                .id(id)
                .nome("Cliente")
                .tipoDocumento(TipoDocumento.CPF)
                .documento(CPF)
                .email("c@c.com")
                .telefone("11999999999")
                .ativo(true)
                .build();
    }

    private static Veiculo veiculo(Cliente c) {
        return Veiculo.builder()
                .id(UUID.randomUUID())
                .placa("ABC1D23")
                .marca("VW")
                .modelo("Gol")
                .ano(2020)
                .cliente(c)
                .ativo(true)
                .build();
    }

    private static Veiculo respostaObterAtivoPorPlaca(AtomicInteger chamadasPlaca, Veiculo novoVeiculo) {
        if (chamadasPlaca.getAndIncrement() == 0) {
            throw veiculoNaoEncontradoPorPlaca();
        }
        return novoVeiculo;
    }

    private static EntityNotFoundException veiculoNaoEncontradoPorPlaca() {
        return new EntityNotFoundException("Veículo", "placa");
    }

    private static OrdemServico osBasica() {
        Cliente c = cliente(UUID.randomUUID());
        Veiculo v = veiculo(c);
        OrdemServico os = OrdemServico.builder()
                .id(UUID.randomUUID())
                .cliente(c)
                .veiculo(v)
                .status(StatusOrdemServico.RECEBIDA)
                .valorTotal(BigDecimal.ZERO)
                .servicos(new ArrayList<>())
                .pecas(new ArrayList<>())
                .historico(new ArrayList<>())
                .build();
        os.setCriadoEm(LocalDateTime.now());
        os.setAtualizadoEm(LocalDateTime.now());
        return os;
    }
}
