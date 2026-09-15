package com.oficina.service;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.config.SecurityUtils;
import com.oficina.domain.identidade.Ator;
import com.oficina.dto.*;
import com.oficina.entity.*;
import com.oficina.exception.BusinessRuleException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.repository.OrdemServicoRepository;
import com.oficina.validation.ValidadorDocumento;
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

    @Value("${oficina.mail.status-token:oficina-email-status-token}")
    private String emailStatusToken;

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
        ordemServicoRepository.flush();
        OrdemServico salva = ordemServicoRepository.findById(os.getId()).orElseThrow();
        notificacaoPort.notificarAtualizacaoStatus(salva, null, "Abertura da ordem de serviço");
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
    public AcompanhamentoOsResponse acompanhamentoPublico(Long numero) {
        OrdemServico os = ordemServicoRepository.findDetalheAcompanhamentoPorNumero(numero)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, numero));

        os.getHistorico().size();
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

        LocalDateTime ultimo = os.getHistorico().stream()
                .max(ORDEM_HISTORICO)
                .map(OsHistorico::getCriadoEm)
                .orElse(os.getCriadoEm());

        return new AcompanhamentoOsResponse(
                os.getNumero(),
                os.getStatus(),
                os.getValorTotal(),
                os.getCriadoEm(),
                ultimo,
                os.getCliente().getNome(),
                os.getVeiculo().getPlaca(),
                historico
        );
    }

    @Transactional
    public OrdemServicoDetalheResponse iniciarDiagnostico(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        StatusOrdemServico anterior = os.getStatus();
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        String obs = acao != null ? acao.observacao() : null;
        os.iniciarDiagnostico(ator, ocorridoEm, obs);
        OrdemServico salva = ordemServicoRepository.save(os);
        notificacaoPort.notificarAtualizacaoStatus(salva, anterior, obs != null ? obs : "Diagnóstico iniciado");
        return montarDetalhe(salva);
    }

    @Transactional
    public OrdemServicoDetalheResponse enviarOrcamento(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        StatusOrdemServico anterior = os.getStatus();
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        String obs = acao != null ? acao.observacao() : "Orçamento enviado ao cliente";
        os.enviarOrcamentoParaAprovacao(ator, ocorridoEm, obs);
        OrdemServico salva = ordemServicoRepository.save(os);
        notificacaoPort.notificarAtualizacaoStatus(salva, anterior, obs);
        return montarDetalhe(salva);
    }

    @Transactional
    public OrdemServicoDetalheResponse processarDecisaoOrcamento(Long numero, DecisaoOrcamentoRequest request) {
        return switch (request.decisao()) {
            case APROVADO -> aprovarPeloCliente(numero, new AprovacaoClienteRequest(request.documentoCliente()));
            case RECUSADO -> recusarPeloCliente(numero, request);
        };
    }

    @Transactional
    public OrdemServicoDetalheResponse aprovarPeloCliente(Long numero, AprovacaoClienteRequest request) {
        OrdemServico os = ordemServicoRepository.findComPecasEClientePorNumero(numero)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, numero));

        validarDocumentoCliente(os, request.documentoCliente());
        validarEstoqueDisponivel(os);

        StatusOrdemServico anterior = os.getStatus();
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        os.aprovarExecucaoCliente(ator, ocorridoEm, "Orçamento aprovado pelo cliente");

        for (OsPecaItem item : os.getPecas()) {
            item.getPeca().baixarEstoque(item.getQuantidade());
        }

        OrdemServico salva = ordemServicoRepository.save(os);
        notificacaoPort.notificarAtualizacaoStatus(salva, anterior, "Orçamento aprovado pelo cliente");
        return montarDetalhe(salva);
    }

    @Transactional
    public OrdemServicoDetalheResponse recusarPeloCliente(Long numero, DecisaoOrcamentoRequest request) {
        OrdemServico os = ordemServicoRepository.findComPecasEClientePorNumero(numero)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, numero));

        validarDocumentoCliente(os, request.documentoCliente());

        StatusOrdemServico anterior = os.getStatus();
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        String obs = request.observacao() != null ? request.observacao() : "Orçamento recusado pelo cliente";
        os.recusarOrcamentoCliente(ator, ocorridoEm, obs);

        OrdemServico salva = ordemServicoRepository.save(os);
        notificacaoPort.notificarAtualizacaoStatus(salva, anterior, obs);
        return montarDetalhe(salva);
    }

    /**
     * Atualiza status da OS a partir de ferramenta de e-mail (link/token no corpo da mensagem).
     * Transições permitidas seguem o mesmo fluxo operacional da oficina.
     */
    @Transactional
    public OrdemServicoDetalheResponse atualizarStatusViaEmail(AtualizacaoStatusEmailRequest request) {
        if (!emailStatusToken.equals(request.token())) {
            throw new BusinessRuleException("Token de atualização por e-mail inválido.");
        }

        StatusOrdemServico destino = request.novoStatus();
        OrdemServico os = (destino == StatusOrdemServico.EM_EXECUCAO)
                ? ordemServicoRepository.findComPecasEClientePorNumero(request.numero())
                    .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, request.numero()))
                : ordemServicoRepository.findByNumero(request.numero())
                    .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, request.numero()));

        StatusOrdemServico anterior = os.getStatus();
        String obs = request.observacao() != null ? request.observacao() : "Atualização via e-mail";
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);

        switch (destino) {
            case EM_DIAGNOSTICO -> os.iniciarDiagnostico(ator, ocorridoEm, obs);
            case AGUARDANDO_APROVACAO -> os.enviarOrcamentoParaAprovacao(ator, ocorridoEm, obs);
            case EM_EXECUCAO -> {
                validarEstoqueDisponivel(os);
                os.aprovarExecucaoCliente(ator, ocorridoEm, obs);
                for (OsPecaItem item : os.getPecas()) {
                    item.getPeca().baixarEstoque(item.getQuantidade());
                }
            }
            case FINALIZADA -> os.finalizarServico(ator, ocorridoEm, obs);
            case ENTREGUE -> os.registrarEntrega(ator, ocorridoEm, obs);
            case RECEBIDA -> throw new BusinessRuleException("Não é possível retornar para RECEBIDA via e-mail.");
        }

        OrdemServico salva = ordemServicoRepository.save(os);
        notificacaoPort.notificarAtualizacaoStatus(salva, anterior, obs);
        return montarDetalhe(salva);
    }

    @Transactional
    public OrdemServicoDetalheResponse finalizar(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        StatusOrdemServico anterior = os.getStatus();
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        String obs = acao != null ? acao.observacao() : null;
        os.finalizarServico(ator, ocorridoEm, obs);
        OrdemServico salva = ordemServicoRepository.save(os);
        notificacaoPort.notificarAtualizacaoStatus(salva, anterior, obs != null ? obs : "Serviço finalizado");
        return montarDetalhe(salva);
    }

    @Transactional
    public OrdemServicoDetalheResponse registrarEntrega(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        StatusOrdemServico anterior = os.getStatus();
        Ator ator = atorAtual();
        Instant ocorridoEm = prepararHistorico(os);
        String obs = acao != null ? acao.observacao() : null;
        os.registrarEntrega(ator, ocorridoEm, obs);
        OrdemServico salva = ordemServicoRepository.save(os);
        notificacaoPort.notificarAtualizacaoStatus(salva, anterior, obs != null ? obs : "Veículo entregue");
        return montarDetalhe(salva);
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
