package com.oficina.service;

import com.oficina.dto.ClienteRequest;
import com.oficina.config.SecurityUtils;
import com.oficina.domain.identidade.DadosIdentidadeCliente;
import com.oficina.repository.ClienteIdentityAuditRepository;
import com.oficina.dto.ClienteResponse;
import com.oficina.entity.Cliente;
import com.oficina.entity.TipoDocumento;
import com.oficina.repository.ClienteRepository;
import com.oficina.exception.DuplicateEntityException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.validation.ValidadorDocumento;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Set;
import java.time.Clock;

@Service
@RequiredArgsConstructor
public class ClienteService {

    private static final String ENTIDADE = "Cliente";

    private final ClienteRepository clienteRepository;
    private final ClienteIdentityAuditRepository identityAuditRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<ClienteResponse> listar(Pageable pageable) {
        return clienteRepository.findByAtivoTrue(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ClienteResponse buscar(UUID id) {
        return clienteRepository.findById(id)
                .filter(Cliente::isAtivo)
                .map(this::toResponse)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
    }

    @Transactional
    public ClienteResponse criar(ClienteRequest request) {
        DadosIdentidadeCliente identidade = new DadosIdentidadeCliente(
                request.tipoDocumento(), request.documento(), request.email(), true);
        String doc = identidade.documento();
        TipoDocumento tipo = identidade.tipoDocumento();

        if (clienteRepository.existsByDocumento(doc)) {
            throw new DuplicateEntityException(ENTIDADE, "documento", doc);
        }

        Cliente cliente = Cliente.builder()
                .nome(request.nome())
                .tipoDocumento(tipo)
                .documento(doc)
                .email(request.email())
                .telefone(request.telefone())
                .cep(request.cep())
                .logradouro(request.logradouro())
                .numero(request.numero())
                .complemento(request.complemento())
                .bairro(request.bairro())
                .cidade(request.cidade())
                .estado(request.estado())
                .ativo(true)
                .build();

        return toResponse(clienteRepository.save(cliente));
    }

    @Transactional
    public ClienteResponse atualizar(UUID id, ClienteRequest request) {
        Cliente cliente = clienteRepository.findById(id)
                .filter(Cliente::isAtivo)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));

        DadosIdentidadeCliente identidade = new DadosIdentidadeCliente(
                request.tipoDocumento(), request.documento(), request.email(), cliente.isAtivo());
        String doc = identidade.documento();

        if (!doc.equals(cliente.getDocumento()) && clienteRepository.existsByDocumento(doc)) {
            throw new DuplicateEntityException(ENTIDADE, "documento", doc);
        }

        atualizarIdentidade(cliente, identidade);
        cliente.setNome(request.nome());
        cliente.setTelefone(request.telefone());
        cliente.setCep(request.cep());
        cliente.setLogradouro(request.logradouro());
        cliente.setNumero(request.numero());
        cliente.setComplemento(request.complemento());
        cliente.setBairro(request.bairro());
        cliente.setCidade(request.cidade());
        cliente.setEstado(request.estado());

        return toResponse(clienteRepository.save(cliente));
    }

    @Transactional
    public void desativar(UUID id) {
        Cliente cliente = clienteRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ENTIDADE, id));
        atualizarIdentidade(cliente, new DadosIdentidadeCliente(
                cliente.getTipoDocumento(), cliente.getDocumento(), cliente.getEmail(), false));
    }

    private void atualizarIdentidade(Cliente cliente, DadosIdentidadeCliente novos) {
        Set<String> campos = cliente.dadosIdentidade().camposAlterados(novos);
        if (campos.isEmpty()) return;
        UUID staffId = SecurityUtils.usuarioAutenticadoId().orElseThrow(
                () -> new AuthenticationCredentialsNotFoundException("Funcionário autenticado é obrigatório."));
        long anterior = cliente.getVersaoIdentidade();
        cliente.atualizarIdentidade(novos);
        identityAuditRepository.registrar(cliente.getId(), staffId, campos,
                anterior, cliente.getVersaoIdentidade(), clock.instant());
    }

    @Transactional(readOnly = true)
    public Cliente obterEntidadeAtivaPorDocumento(String documento) {
        String doc = ValidadorDocumento.normalizarDigitos(documento);
        ValidadorDocumento.validarCpfOuCnpj(doc);
        return clienteRepository.findByDocumento(doc)
                .filter(Cliente::isAtivo)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Cliente com documento informado não encontrado."
                ));
    }

    private ClienteResponse toResponse(Cliente c) {
        return new ClienteResponse(
                c.getId(),
                c.getNome(),
                c.getTipoDocumento(),
                ValidadorDocumento.formatarParaExibicao(c.getDocumento()),
                c.getDocumento(),
                c.getEmail(),
                c.getTelefone(),
                c.getCep(),
                c.getLogradouro(),
                c.getNumero(),
                c.getComplemento(),
                c.getBairro(),
                c.getCidade(),
                c.getEstado(),
                c.isAtivo()
        );
    }
}
