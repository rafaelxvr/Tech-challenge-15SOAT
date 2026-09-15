package com.oficina.service;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import com.oficina.config.SecurityUtils;
import com.oficina.domain.identidade.Ator;
import com.oficina.dto.*;
import com.oficina.entity.*;
import com.oficina.exception.BusinessRuleException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.repository.OrdemServicoRepository;
import com.oficina.validation.ValidadorDocumento;
import com.oficina.security.IdentidadeAutenticada;
import com.oficina.security.TipoPrincipal;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrdemServicoService {

    private static final String ENTIDADE = "Ordem de serviço";
    private static final Comparator<OsHistorico> ORDEM_HISTORICO = Comparator
            .comparing(OsHistorico::getSequencia, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(OsHistorico::getCriadoEm);

    /** Status excluídos logicamente da listagem operacional (não são apagados do banco). */
    private static final Set<StatusOrdemServico> STATUS_EXCLUIDOS_LISTAGEM = EnumSet.of(
            StatusOrdemServico.FINALIZADA,
            StatusOrdemServico.ENTREGUE
    );

    private final OrdemServicoRepository ordemServicoRepository;
    private final ClienteService clienteService;
    private final VeiculoService veiculoService;
    private final ServicoService servicoService;
    private final PecaService pecaService;
    private final NotificacaoPort notificacaoPort;
    private final Clock clock;

    @Value("${oficina.historico.zona-compatibilidade}")
    private String zonaCompatibilidade;

    private Instant prepararHistorico(OrdemServico os) {
        os.setZonaCompatibilidade(ZoneId.of(zonaCompatibilidade));
        return clock.instant();
    }

    private Ator atorAtual() {
        return SecurityUtils.usuarioAutenticadoId().map(Ator::staff).orElseGet(Ator::sistema);
    }

    @Transactional
    public OrdemServicoDetalheResponse criar(CriarOrdemServicoRequest request) {
        Cliente cliente = clienteService.obterEntidadeAtivaPorDocumento(request.documentoCliente());
        Veiculo veiculo = resolverVeiculo(request, cliente);

        OrdemServico os = OrdemServico.builder()
                .cliente(cliente)
                .veiculo(veiculo)
                .status(StatusOrdemServico.RECEBIDA)
                .observacoes(request.observacoes())
                .valorTotal(BigDecimal.ZERO)
                .build();

        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        os.registrarHistoricoInicial(ator, ocorridoEm, "Abertura da ordem de serviço");

        for (ItemServicoOsRequest item : request.servicos()) {
            var servico = servicoService.obterAtivo(item.servicoId());
            BigDecimal vu = servico.getValor();
            BigDecimal vt = vu.multiply(BigDecimal.valueOf(item.quantidade()));
            os.adicionarServico(OsServicoItem.builder()
                    .servico(servico)
                    .quantidade(item.quantidade())
                    .valorUnitario(vu)
                    .valorTotal(vt)
                    .observacao(item.observacao())
                    .build());
        }

        List<ItemPecaOsRequest> pecas = request.pecas() != null ? request.pecas() : List.of();
        for (ItemPecaOsRequest item : pecas) {
            var peca = pecaService.obterAtiva(item.pecaId());
            BigDecimal vu = peca.getValorUnitario();
            BigDecimal vt = vu.multiply(BigDecimal.valueOf(item.quantidade()));
            os.adicionarPeca(OsPecaItem.builder()
                    .peca(peca)
                    .quantidade(item.quantidade())
                    .valorUnitario(vu)
                    .valorTotal(vt)
                    .build());
        }

        os.recalcularValorTotal();
        ordemServicoRepository.save(os);
        OrdemServico salva = os;
        registrarNotificacao(salva);
        return montarDetalhe(salva);
    }

    /**
     * Listagem operacional: prioridade Em Execução > Aguardando Aprovação > Diagnóstico > Recebida,
     * mais antigas primeiro; FINALIZADA e ENTREGUE ficam de fora (exclusão lógica).
     */
    @Transactional(readOnly = true)
    public Page<OrdemServicoResumoResponse> listar(StatusOrdemServico status, Pageable pageable) {
        if (status != null && STATUS_EXCLUIDOS_LISTAGEM.contains(status)) {
            throw new BusinessRuleException(
                    "Ordens FINALIZADA/ENTREGUE não entram na listagem operacional. Use a consulta por id ou número."
            );
        }
        Pageable semSortCliente = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<OrdemServico> page = ordemServicoRepository.findAtivasOrdenadasPorPrioridade(
                status, STATUS_EXCLUIDOS_LISTAGEM, semSortCliente);
        return page.map(this::toResumo);
    }

    @Transactional(readOnly = true)
    public OrdemServicoDetalheResponse buscarPorId(UUID id) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        return montarDetalhe(os);
    }

    @Transactional(readOnly = true)
    public AcompanhamentoOsResponse acompanhamentoDoCliente(Long numero, IdentidadeAutenticada cliente) {
        exigirCliente(cliente, "SCOPE_orders:read:self");
        OrdemServico os = ordemServicoRepository.findDetalheAcompanhamentoPorNumero(numero)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, numero));
        exigirProprietario(os, numero, cliente);
        return montarAcompanhamento(os);
    }

    private AcompanhamentoOsResponse montarAcompanhamento(OrdemServico os) {
        var historico = os.getHistorico().stream().sorted(ORDEM_HISTORICO)
                .map(h -> new AcompanhamentoOsResponse.HistoricoCliente(
                        h.getStatusAnterior(), h.getStatusNovo(), h.getCriadoEm(), h.getOcorridoEm()))
                .toList();
        LocalDateTime ultimo = os.getHistorico().stream().max(ORDEM_HISTORICO)
                .map(OsHistorico::getCriadoEm).orElse(os.getCriadoEm());
        var servicos = os.getServicos().stream()
                .map(s -> new AcompanhamentoOsResponse.LinhaOrcamento(s.getServico().getNome(),
                        s.getQuantidade(), s.getValorUnitario(), s.getValorTotal())).toList();
        var pecas = os.getPecas().stream()
                .map(p -> new AcompanhamentoOsResponse.LinhaOrcamento(p.getPeca().getNome(),
                        p.getQuantidade(), p.getValorUnitario(), p.getValorTotal())).toList();
        return new AcompanhamentoOsResponse(os.getNumero(), os.getStatus(), os.getValorTotal(),
                os.getCriadoEm(), ultimo, servicos, pecas, historico);
    }

    @Transactional
    public OrdemServicoDetalheResponse iniciarDiagnostico(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        String obs = acao != null ? acao.observacao() : null;
        os.iniciarDiagnostico(ator, ocorridoEm, obs);
        OrdemServico salva = ordemServicoRepository.save(os);
        registrarNotificacao(salva);
        return montarDetalhe(salva);
    }

    @Transactional
    public OrdemServicoDetalheResponse enviarOrcamento(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        String obs = acao != null ? acao.observacao() : "Orçamento enviado ao cliente";
        os.enviarOrcamentoParaAprovacao(ator, ocorridoEm, obs);
        OrdemServico salva = ordemServicoRepository.save(os);
        registrarNotificacao(salva);
        return montarDetalhe(salva);
    }

    @Transactional
    public AcompanhamentoOsResponse decidirComoCliente(Long numero, IdentidadeAutenticada cliente,
                                                     DecisaoClienteRequest pedido) {
        return executarDecisaoCliente(numero, cliente, pedido, null);
    }

    /** Compatibility input can only agree with the owner already selected by the signed principal. */
    @Transactional
    public AcompanhamentoOsResponse decidirComoClienteLegado(Long numero, IdentidadeAutenticada cliente,
                                                           DecisaoOrcamentoRequest pedido) {
        return executarDecisaoCliente(numero, cliente,
                new DecisaoClienteRequest(pedido.decisao(), pedido.observacao()), pedido.documentoCliente());
    }

    private AcompanhamentoOsResponse executarDecisaoCliente(Long numero, IdentidadeAutenticada cliente,
                                                          DecisaoClienteRequest pedido, String documentoLegado) {
        exigirCliente(cliente, "SCOPE_orders:decide:self");
        OrdemServico os = ordemServicoRepository.findComPecasEClientePorNumero(numero)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, numero));
        exigirProprietario(os, numero, cliente);
        if (documentoLegado != null) validarDocumentoCliente(os, documentoLegado);

        Ator ator = Ator.cliente(cliente.id());
        Instant ocorridoEm = prepararHistorico(os);
        boolean aprovado = pedido.decisao() == DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO;
        String observacao = pedido.observacao() != null ? pedido.observacao()
                : aprovado ? "Orçamento aprovado pelo cliente" : "Orçamento recusado pelo cliente";
        if (aprovado) {
            validarEstoqueDisponivel(os);
            os.aprovarExecucaoCliente(ator, ocorridoEm, observacao);
            for (OsPecaItem item : os.getPecas()) item.getPeca().baixarEstoque(item.getQuantidade());
        } else {
            os.recusarOrcamentoCliente(ator, ocorridoEm, observacao);
        }
        OrdemServico salva = ordemServicoRepository.save(os);
        registrarNotificacao(salva);
        return montarAcompanhamento(salva);
    }

    private void exigirCliente(IdentidadeAutenticada cliente, String permissao) {
        if (cliente == null || cliente.tipo() != TipoPrincipal.CUSTOMER || !cliente.permissoes().contains(permissao)) {
            throw new AccessDeniedException("Identidade de cliente e escopo próprio obrigatórios.");
        }
    }

    private void exigirProprietario(OrdemServico os, Long numero, IdentidadeAutenticada cliente) {
        if (!os.getCliente().getId().equals(cliente.id())) {
            throw new EntityNotFoundException(ENTIDADE, numero);
        }
    }

    @Transactional
    public OrdemServicoDetalheResponse finalizar(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        String obs = acao != null ? acao.observacao() : null;
        os.finalizarServico(ator, ocorridoEm, obs);
        OrdemServico salva = ordemServicoRepository.save(os);
        registrarNotificacao(salva);
        return montarDetalhe(salva);
    }

    @Transactional
    public OrdemServicoDetalheResponse registrarEntrega(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        String obs = acao != null ? acao.observacao() : null;
        os.registrarEntrega(ator, ocorridoEm, obs);
        OrdemServico salva = ordemServicoRepository.save(os);
        registrarNotificacao(salva);
        return montarDetalhe(salva);
    }

    private void registrarNotificacao(OrdemServico os) {
        // Persist generated order number, optimistic versions and canonical history before JDBC insertion.
        ordemServicoRepository.flush();
        OsHistorico historico = os.getHistorico().stream()
                .filter(h -> h.getSequencia() != null && h.getSequencia() == os.getSequenciaHistorico())
                .findFirst().orElseThrow(() -> new IllegalStateException("Canonical history required"));
        notificacaoPort.notificarAtualizacaoStatus(new StatusOrdemServicoRegistrado(
                UUID.randomUUID(), StatusOrdemServicoRegistrado.EVENT_TYPE,
                StatusOrdemServicoRegistrado.SCHEMA_VERSION, os.getId(), os.getNumero(),
                os.getCliente().getId(), os.getCliente().getVersaoIdentidade(), historico.getSequencia(),
                historico.getStatusAnterior() == null ? null : historico.getStatusAnterior().name(),
                historico.getStatusNovo().name(), historico.getOcorridoEm(), UUID.randomUUID().toString(), null));
    }

    private void validarDocumentoCliente(OrdemServico os, String documentoInformado) {
        String doc = ValidadorDocumento.normalizarDigitos(documentoInformado);
        ValidadorDocumento.validarCpfOuCnpj(doc);
        if (!doc.equals(os.getCliente().getDocumento())) {
            throw new BusinessRuleException("Documento não confere com o cliente desta ordem de serviço.");
        }
    }

    private void validarEstoqueDisponivel(OrdemServico os) {
        for (OsPecaItem item : os.getPecas()) {
            Peca peca = item.getPeca();
            if (peca.getQuantidadeEstoque() < item.getQuantidade()) {
                throw new BusinessRuleException(
                        "Estoque insuficiente para aprovação. Peça %s: disponível %d, necessário %d."
                                .formatted(peca.getCodigo(), peca.getQuantidadeEstoque(), item.getQuantidade())
                );
            }
        }
    }

    private Veiculo resolverVeiculo(CriarOrdemServicoRequest request, Cliente cliente) {
        try {
            Veiculo v = veiculoService.obterAtivoPorPlaca(request.placaVeiculo());
            if (!v.getCliente().getId().equals(cliente.getId())) {
                throw new BusinessRuleException("A placa informada pertence a outro cliente.");
            }
            return v;
        } catch (EntityNotFoundException ex) {
            if (request.marca() == null || request.modelo() == null || request.ano() == null) {
                throw new BusinessRuleException(
                        "Veículo não cadastrado. Informe marca, modelo e ano para cadastro automático."
                );
            }
            VeiculoRequest vr = new VeiculoRequest(
                    request.placaVeiculo(),
                    request.marca(),
                    request.modelo(),
                    request.ano(),
                    null,
                    null,
                    cliente.getId()
            );
            VeiculoResponse criado = veiculoService.criar(vr);
            return veiculoService.obterAtivoPorPlaca(criado.placa());
        }
    }

    private OrdemServicoResumoResponse toResumo(OrdemServico os) {
        return new OrdemServicoResumoResponse(
                os.getId(),
                os.getNumero(),
                os.getStatus(),
                os.getCliente().getNome(),
                os.getVeiculo().getPlaca(),
                os.getValorTotal(),
                os.getCriadoEm()
        );
    }

    private OrdemServicoDetalheResponse montarDetalhe(OrdemServico os) {
        os.getServicos().size();
        os.getPecas().size();
        os.getHistorico().size();

        var cliente = new OrdemServicoDetalheResponse.ClienteResumo(
                os.getCliente().getId(),
                os.getCliente().getNome(),
                ValidadorDocumento.formatarParaExibicao(os.getCliente().getDocumento())
        );
        var veiculo = new OrdemServicoDetalheResponse.VeiculoResumo(
                os.getVeiculo().getId(),
                os.getVeiculo().getPlaca(),
                os.getVeiculo().getMarca(),
                os.getVeiculo().getModelo(),
                os.getVeiculo().getAno()
        );

        List<OrdemServicoDetalheResponse.ServicoLinhaResponse> servicos = os.getServicos().stream()
                .map(s -> new OrdemServicoDetalheResponse.ServicoLinhaResponse(
                        s.getId(),
                        s.getServico().getId(),
                        s.getServico().getNome(),
                        s.getQuantidade(),
                        s.getValorUnitario(),
                        s.getValorTotal(),
                        s.getObservacao()
                ))
                .toList();

        List<OrdemServicoDetalheResponse.PecaLinhaResponse> pecas = os.getPecas().stream()
                .map(p -> new OrdemServicoDetalheResponse.PecaLinhaResponse(
                        p.getId(),
                        p.getPeca().getId(),
                        p.getPeca().getCodigo(),
                        p.getPeca().getNome(),
                        p.getQuantidade(),
                        p.getValorUnitario(),
                        p.getValorTotal()
                ))
                .toList();

        List<OrdemServicoDetalheResponse.HistoricoStatusResponse> historico = os.getHistorico().stream()
                .sorted(ORDEM_HISTORICO)
                .map(h -> new OrdemServicoDetalheResponse.HistoricoStatusResponse(
                        h.getStatusAnterior(),
                        h.getStatusNovo(),
                        h.getObservacao(),
                        h.getCriadoEm(),
                        h.getOcorridoEm()
                ))
                .toList();

        return new OrdemServicoDetalheResponse(
                os.getId(),
                os.getNumero(),
                os.getStatus(),
                os.getValorTotal(),
                os.getObservacoes(),
                os.getAprovadoEm(),
                os.getIniciadoEm(),
                os.getFinalizadoEm(),
                os.getEntregueEm(),
                os.getCriadoEm(),
                cliente,
                veiculo,
                servicos,
                pecas,
                historico
        );
    }
}
