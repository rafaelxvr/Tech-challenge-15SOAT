package com.oficina.service;

import com.oficina.dto.VeiculoRequest;
import com.oficina.dto.VeiculoResponse;
import com.oficina.entity.Cliente;
import com.oficina.repository.ClienteRepository;
import com.oficina.exception.DuplicateEntityException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.validation.ValidadorPlaca;
import com.oficina.entity.Veiculo;
import com.oficina.repository.VeiculoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VeiculoService {

    private static final String ENTIDADE = "Veículo";

    private final VeiculoRepository veiculoRepository;
    private final ClienteRepository clienteRepository;

    @Transactional(readOnly = true)
    public Page<VeiculoResponse> listar(Pageable pageable) {
        return veiculoRepository.findByAtivoTrue(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<VeiculoResponse> listarPorCliente(UUID clienteId, Pageable pageable) {
        return veiculoRepository.findByClienteIdAndAtivoTrue(clienteId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public VeiculoResponse buscar(UUID id) {
        return veiculoRepository.findById(id)
                .filter(Veiculo::isAtivo)
                .map(this::toResponse)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
    }

    @Transactional
    public VeiculoResponse criar(VeiculoRequest request) {
        String placa = ValidadorPlaca.normalizar(request.placa());
        ValidadorPlaca.validar(placa);

        Cliente cliente = clienteRepository.findById(request.clienteId())
                .filter(Cliente::isAtivo)
                .orElseThrow(() -> new EntityNotFoundException("Cliente", request.clienteId()));

        var existente = veiculoRepository.findByPlaca(placa);
        if (existente.isPresent()) {
            Veiculo v = existente.get();
            if (v.isAtivo()) {
                throw new DuplicateEntityException(ENTIDADE, "placa", placa);
            }
            v.setCliente(cliente);
            v.setMarca(request.marca());
            v.setModelo(request.modelo());
            v.setAno(request.ano());
            v.setCor(request.cor());
            v.setChassi(request.chassi());
            v.setAtivo(true);
            return toResponse(veiculoRepository.save(v));
        }

        Veiculo veiculo = Veiculo.builder()
                .placa(placa)
                .marca(request.marca())
                .modelo(request.modelo())
                .ano(request.ano())
                .cor(request.cor())
                .chassi(request.chassi())
                .cliente(cliente)
                .ativo(true)
                .build();

        return toResponse(veiculoRepository.save(veiculo));
    }

    @Transactional
    public VeiculoResponse atualizar(UUID id, VeiculoRequest request) {
        Veiculo veiculo = veiculoRepository.findById(id)
                .filter(Veiculo::isAtivo)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));

        String placa = ValidadorPlaca.normalizar(request.placa());
        ValidadorPlaca.validar(placa);

        veiculoRepository.findByPlaca(placa).ifPresent(outro -> {
            if (outro.isAtivo() && !outro.getId().equals(id)) {
                throw new DuplicateEntityException(ENTIDADE, "placa", placa);
            }
        });

        Cliente cliente = clienteRepository.findById(request.clienteId())
                .filter(Cliente::isAtivo)
                .orElseThrow(() -> new EntityNotFoundException("Cliente", request.clienteId()));

        veiculo.setPlaca(placa);
        veiculo.setMarca(request.marca());
        veiculo.setModelo(request.modelo());
        veiculo.setAno(request.ano());
        veiculo.setCor(request.cor());
        veiculo.setChassi(request.chassi());
        veiculo.setCliente(cliente);

        return toResponse(veiculoRepository.save(veiculo));
    }

    @Transactional
    public void desativar(UUID id) {
        Veiculo veiculo = veiculoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        veiculo.setAtivo(false);
    }

    @Transactional(readOnly = true)
    public Veiculo obterAtivoPorPlaca(String placa) {
        String p = ValidadorPlaca.normalizar(placa);
        ValidadorPlaca.validar(p);
        return veiculoRepository.findByPlaca(p)
                .filter(Veiculo::isAtivo)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Veículo com a placa informada não encontrado."
                ));
    }

    private VeiculoResponse toResponse(Veiculo v) {
        return new VeiculoResponse(
                v.getId(),
                v.getPlaca(),
                v.getMarca(),
                v.getModelo(),
                v.getAno(),
                v.getCor(),
                v.getChassi(),
                v.getCliente().getId(),
                v.isAtivo()
        );
    }
}
