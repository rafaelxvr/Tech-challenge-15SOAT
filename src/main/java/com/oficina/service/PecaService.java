package com.oficina.service;

import com.oficina.dto.PecaRequest;
import com.oficina.dto.PecaResponse;
import com.oficina.exception.DuplicateEntityException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.entity.Peca;
import com.oficina.repository.PecaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PecaService {

    private final PecaRepository pecaRepository;

    @Transactional(readOnly = true)
    public Page<PecaResponse> listar(Pageable pageable) {
        return pecaRepository.findByAtivoTrue(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PecaResponse buscar(UUID id) {
        return pecaRepository.findById(id)
                .filter(Peca::isAtivo)
                .map(this::toResponse)
                .orElseThrow(() -> new EntityNotFoundException("Peça", id));
    }

    @Transactional
    public PecaResponse criar(PecaRequest request) {
        if (pecaRepository.findByCodigo(request.codigo()).isPresent()) {
            throw new DuplicateEntityException("Peça", "codigo", request.codigo());
        }
        Peca peca = Peca.builder()
                .codigo(request.codigo())
                .nome(request.nome())
                .descricao(request.descricao())
                .valorUnitario(request.valorUnitario())
                .quantidadeEstoque(request.quantidadeEstoque())
                .quantidadeMinima(request.quantidadeMinima())
                .unidadeMedida(request.unidadeMedida())
                .ativo(true)
                .build();
        return toResponse(pecaRepository.save(peca));
    }

    @Transactional
    public PecaResponse atualizar(UUID id, PecaRequest request) {
        Peca peca = pecaRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Peça", id));

        pecaRepository.findByCodigo(request.codigo()).ifPresent(outra -> {
            if (!outra.getId().equals(id)) {
                throw new DuplicateEntityException("Peça", "codigo", request.codigo());
            }
        });

        peca.setCodigo(request.codigo());
        peca.setNome(request.nome());
        peca.setDescricao(request.descricao());
        peca.setValorUnitario(request.valorUnitario());
        peca.setQuantidadeEstoque(request.quantidadeEstoque());
        peca.setQuantidadeMinima(request.quantidadeMinima());
        peca.setUnidadeMedida(request.unidadeMedida());

        return toResponse(pecaRepository.save(peca));
    }

    @Transactional
    public void desativar(UUID id) {
        Peca peca = pecaRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Peça", id));
        peca.setAtivo(false);
    }

    @Transactional(readOnly = true)
    public Peca obterAtiva(UUID id) {
        return pecaRepository.findById(id)
                .filter(Peca::isAtivo)
                .orElseThrow(() -> new EntityNotFoundException("Peça", id));
    }

    private PecaResponse toResponse(Peca p) {
        return new PecaResponse(
                p.getId(),
                p.getCodigo(),
                p.getNome(),
                p.getDescricao(),
                p.getValorUnitario(),
                p.getQuantidadeEstoque(),
                p.getQuantidadeMinima(),
                p.getUnidadeMedida(),
                p.isAtivo()
        );
    }
}
