package com.oficina.service;

import com.oficina.dto.ClienteRequest;
import com.oficina.dto.ClienteResponse;
import com.oficina.entity.Cliente;
import com.oficina.entity.TipoDocumento;
import com.oficina.exception.DuplicateEntityException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.repository.ClienteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClienteServiceTest {

    private static final String CPF_VALIDO = "39053344705";

    @Mock
    private ClienteRepository clienteRepository;

    @InjectMocks
    private ClienteService clienteService;

    @Test
    void listar_delegaRepository() {
        Pageable p = PageRequest.of(0, 10);
        Cliente c = clienteAtivo();
        when(clienteRepository.findByAtivoTrue(p)).thenReturn(new PageImpl<>(List.of(c)));

        Page<ClienteResponse> page = clienteService.listar(p);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).nome()).isEqualTo("João");
    }

    @Test
    void buscar_encontrado() {
        UUID id = UUID.randomUUID();
        Cliente c = clienteAtivo();
        when(clienteRepository.findById(id)).thenReturn(Optional.of(c));

        ClienteResponse r = clienteService.buscar(id);

        assertThat(r.id()).isEqualTo(c.getId());
    }

    @Test
    void buscar_inativo_lancaNotFound() {
        UUID id = UUID.randomUUID();
        Cliente c = clienteAtivo();
        c.setAtivo(false);
        when(clienteRepository.findById(id)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> clienteService.buscar(id)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void criar_persisteCliente() {
        ClienteRequest req = requestPadrao();
        when(clienteRepository.existsByDocumento(CPF_VALIDO)).thenReturn(false);
        Cliente salvo = clienteAtivo();
        when(clienteRepository.save(any(Cliente.class))).thenAnswer(inv -> inv.getArgument(0));

        ClienteResponse r = clienteService.criar(req);

        assertThat(r.documentoSomenteDigitos()).isEqualTo(CPF_VALIDO);
        verify(clienteRepository).save(any(Cliente.class));
    }

    @Test
    void criar_documentoDuplicado_lanca() {
        ClienteRequest req = requestPadrao();
        when(clienteRepository.existsByDocumento(CPF_VALIDO)).thenReturn(true);

        assertThatThrownBy(() -> clienteService.criar(req)).isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void atualizar_alteraCampos() {
        UUID id = UUID.randomUUID();
        Cliente existente = clienteAtivo();
        existente.setId(id);
        when(clienteRepository.findById(id)).thenReturn(Optional.of(existente));
        when(clienteRepository.save(any(Cliente.class))).thenAnswer(inv -> inv.getArgument(0));

        ClienteRequest req = requestPadrao();
        ClienteResponse r = clienteService.atualizar(id, req);

        assertThat(r.email()).isEqualTo("joao@email.com");
    }

    @Test
    void obterEntidadeAtivaPorDocumento_retornaCliente() {
        Cliente c = clienteAtivo();
        when(clienteRepository.findByDocumento(CPF_VALIDO)).thenReturn(Optional.of(c));

        Cliente out = clienteService.obterEntidadeAtivaPorDocumento("390.533.447-05");

        assertThat(out.getDocumento()).isEqualTo(CPF_VALIDO);
    }

    private static Cliente clienteAtivo() {
        return Cliente.builder()
                .id(UUID.randomUUID())
                .nome("João")
                .tipoDocumento(TipoDocumento.CPF)
                .documento(CPF_VALIDO)
                .email("joao@email.com")
                .telefone("11999999999")
                .ativo(true)
                .build();
    }

    private static ClienteRequest requestPadrao() {
        return new ClienteRequest(
                "João",
                TipoDocumento.CPF,
                "390.533.447-05",
                "joao@email.com",
                "11999999999",
                null, null, null, null, null, null, null
        );
    }
}
