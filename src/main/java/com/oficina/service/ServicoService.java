package com.oficina.service;

import com.oficina.dto.ServicoRequest;
import com.oficina.dto.ServicoResponse;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.entity.Servico;
import com.oficina.repository.ServicoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServicoService {

    private static final String ENTIDADE = "Serviço";

    private final ServicoRepository servicoRepository;

    @Transactional(readOnly = true)
    public Page<ServicoResponse> listar(Pageable pageable) {
        return servicoRepository.findByAtivoTrue(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ServicoResponse buscar(UUID id) {
        return servicoRepository.findById(id)
                .filter(Servico::isAtivo)
                .map(this::toResponse)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
    }

    @Transactional
    public ServicoResponse criar(ServicoRequest request) {
        Servico servico = Servico.builder()
                .nome(request.nome())
                .descricao(request.descricao())
                .valor(request.valor())
                .tempoEstimadoMin(request.tempoEstimadoMin())
                .ativo(true)
                .build();
        return toResponse(servicoRepository.save(servico));
    }

    @Transactional
    public ServicoResponse atualizar(UUID id, ServicoRequest request) {
        Servico servico = servicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        servico.setNome(request.nome());
        servico.setDescricao(request.descricao());
        servico.setValor(request.valor());
        servico.setTempoEstimadoMin(request.tempoEstimadoMin());
        return toResponse(servicoRepository.save(servico));
    }

    @Transactional
    public void desativar(UUID id) {
        Servico servico = servicoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        servico.setAtivo(false);
    }

    @Transactional(readOnly = true)
    public Servico obterAtivo(UUID id) {
        return servicoRepository.findById(id)
                .filter(Servico::isAtivo)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
    }

    private ServicoResponse toResponse(Servico s) {
        return new ServicoResponse(
                s.getId(),
                s.getNome(),
                s.getDescricao(),
                s.getValor(),
                s.getTempoEstimadoMin(),
                s.isAtivo()
        );
    }
}
