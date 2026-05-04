package com.oficina.service;

import com.oficina.service.ClienteService;
import com.oficina.dto.*;
import com.oficina.service.PecaService;
import com.oficina.service.ServicoService;
import com.oficina.service.VeiculoService;
import com.oficina.dto.VeiculoRequest;
import com.oficina.dto.VeiculoResponse;
import com.oficina.entity.Cliente;
import com.oficina.exception.BusinessRuleException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.entity.*;
import com.oficina.repository.OrdemServicoRepository;
import com.oficina.entity.Peca;
import com.oficina.validation.ValidadorDocumento;
import com.oficina.entity.Veiculo;
import com.oficina.config.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrdemServicoService {

    private final OrdemServicoRepository ordemServicoRepository;
    private final ClienteService clienteService;
    private final VeiculoService veiculoService;
    private final ServicoService servicoService;
    private final PecaService pecaService;

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

        UUID usuarioId = SecurityUtils.usuarioAutenticadoId().orElse(null);
        os.registrarHistoricoInicial(usuarioId, "Abertura da ordem de serviço");

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
        return montarDetalhe(ordemServicoRepository.findById(os.getId()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public Page<OrdemServicoResumoResponse> listar(StatusOrdemServico status, Pageable pageable) {
        Page<OrdemServico> page = status == null
                ? ordemServicoRepository.findAll(pageable)
                : ordemServicoRepository.findByStatus(status, pageable);
        return page.map(this::toResumo);
    }

    @Transactional(readOnly = true)
    public OrdemServicoDetalheResponse buscarPorId(UUID id) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Ordem de serviço", id));
        return montarDetalhe(os);
    }

    @Transactional(readOnly = true)
    public AcompanhamentoOsResponse acompanhamentoPublico(Long numero) {
        OrdemServico os = ordemServicoRepository.findDetalheAcompanhamentoPorNumero(numero)
                .orElseThrow(() -> new EntityNotFoundException("Ordem de serviço", numero));

        os.getHistorico().size();
        List<OrdemServicoDetalheResponse.HistoricoStatusResponse> historico = os.getHistorico().stream()
                .sorted(Comparator.comparing(OsHistorico::getCriadoEm))
                .map(h -> new OrdemServicoDetalheResponse.HistoricoStatusResponse(
                        h.getStatusAnterior(),
                        h.getStatusNovo(),
                        h.getObservacao(),
                        h.getCriadoEm()
                ))
                .toList();

        LocalDateTime ultimo = os.getHistorico().stream()
                .map(OsHistorico::getCriadoEm)
                .max(Comparator.naturalOrder())
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
                .orElseThrow(() -> new EntityNotFoundException("Ordem de serviço", id));
        UUID usuarioId = SecurityUtils.usuarioAutenticadoId().orElse(null);
        os.iniciarDiagnostico(usuarioId, acao != null ? acao.observacao() : null);
        return montarDetalhe(ordemServicoRepository.save(os));
    }

    @Transactional
    public OrdemServicoDetalheResponse enviarOrcamento(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Ordem de serviço", id));
        UUID usuarioId = SecurityUtils.usuarioAutenticadoId().orElse(null);
        os.enviarOrcamentoParaAprovacao(usuarioId, acao != null ? acao.observacao() : "Orçamento enviado ao cliente");
        return montarDetalhe(ordemServicoRepository.save(os));
    }

    @Transactional
    public OrdemServicoDetalheResponse aprovarPeloCliente(Long numero, AprovacaoClienteRequest request) {
        OrdemServico os = ordemServicoRepository.findComPecasEClientePorNumero(numero)
                .orElseThrow(() -> new EntityNotFoundException("Ordem de serviço", numero));

        String doc = ValidadorDocumento.normalizarDigitos(request.documentoCliente());
        ValidadorDocumento.validarCpfOuCnpj(doc);
        if (!doc.equals(os.getCliente().getDocumento())) {
            throw new BusinessRuleException("Documento não confere com o cliente desta ordem de serviço.");
        }

        validarEstoqueDisponivel(os);

        UUID usuarioId = SecurityUtils.usuarioAutenticadoId().orElse(null);
        os.aprovarExecucaoCliente(usuarioId, "Orçamento aprovado pelo cliente");

        for (OsPecaItem item : os.getPecas()) {
            item.getPeca().baixarEstoque(item.getQuantidade());
        }

        return montarDetalhe(ordemServicoRepository.save(os));
    }

    @Transactional
    public OrdemServicoDetalheResponse finalizar(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Ordem de serviço", id));
        UUID usuarioId = SecurityUtils.usuarioAutenticadoId().orElse(null);
        os.finalizarServico(usuarioId, acao != null ? acao.observacao() : null);
        return montarDetalhe(ordemServicoRepository.save(os));
    }

    @Transactional
    public OrdemServicoDetalheResponse registrarEntrega(UUID id, AcaoOrdemRequest acao) {
        OrdemServico os = ordemServicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Ordem de serviço", id));
        UUID usuarioId = SecurityUtils.usuarioAutenticadoId().orElse(null);
        os.registrarEntrega(usuarioId, acao != null ? acao.observacao() : null);
        return montarDetalhe(ordemServicoRepository.save(os));
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
                .sorted(Comparator.comparing(OsHistorico::getCriadoEm))
                .map(h -> new OrdemServicoDetalheResponse.HistoricoStatusResponse(
                        h.getStatusAnterior(),
                        h.getStatusNovo(),
                        h.getObservacao(),
                        h.getCriadoEm()
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
