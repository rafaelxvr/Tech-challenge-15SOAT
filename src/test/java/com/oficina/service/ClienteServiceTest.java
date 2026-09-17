package com.oficina.service;

import com.oficina.dto.ClienteRequest;
import com.oficina.domain.identidade.DadosIdentidadeCliente;
import com.oficina.entity.Usuario;
import com.oficina.repository.ClienteIdentityAuditRepository;
import com.oficina.dto.ClienteResponse;
import com.oficina.entity.Cliente;
import com.oficina.entity.TipoDocumento;
import com.oficina.exception.DuplicateEntityException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.repository.ClienteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Set;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ClienteServiceTest {

    private static final String CPF_VALIDO = "39053344705";

    @Mock
    private ClienteRepository clienteRepository;

    @Mock private ClienteIdentityAuditRepository identityAuditRepository;
    @Mock private Clock clock;

    @InjectMocks
    private ClienteService clienteService;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

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
        c.atualizarIdentidade(new DadosIdentidadeCliente(
                c.getTipoDocumento(), c.getDocumento(), c.getEmail(), false));
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
        assertThat(existente.getVersaoIdentidade()).isEqualTo(1);
        verifyNoInteractions(identityAuditRepository, clock);
    }

    @Test void atualizar_identidadeRegistraFuncionarioCamposVersoesEHorario() {
        Cliente c = clienteAtivo();
        UUID staffId = autenticar();
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clienteRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(clienteRepository.save(c)).thenReturn(c);
        ClienteRequest request = new ClienteRequest("Novo nome", TipoDocumento.CNPJ, "11.222.333/0001-81",
                "changed@example.invalid", "11999999999", null, null, null, null, null, null, null);

        clienteService.atualizar(c.getId(), request);

        verify(identityAuditRepository).registrar(c.getId(), staffId,
                Set.of("tipo_documento", "documento", "email"), 1, 2, now);
        assertThat(c.getVersaoIdentidade()).isEqualTo(2);
    }

    @Test void desativar_auditaUmaVez() {
        Cliente c = clienteAtivo();
        UUID staffId = autenticar();
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clienteRepository.findById(c.getId())).thenReturn(Optional.of(c));
        clienteService.desativar(c.getId());
        clienteService.desativar(c.getId());
        assertThat(c.isAtivo()).isFalse();
        assertThat(c.getVersaoIdentidade()).isEqualTo(2);
        verify(identityAuditRepository).registrar(c.getId(), staffId, Set.of("ativo"), 1, 2, now);
    }

    @Test void desativar_semFuncionarioRejeitaAntesDeAlterarIdentidade() {
        Cliente c = clienteAtivo();
        when(clienteRepository.findById(c.getId())).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> clienteService.desativar(c.getId()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(c.isAtivo()).isTrue();
        assertThat(c.getVersaoIdentidade()).isEqualTo(1);
        verifyNoInteractions(identityAuditRepository, clock);
    }

    private static UUID autenticar() {
        UUID id = UUID.randomUUID();
        Usuario usuario = Usuario.builder().id(id).role(Usuario.Role.ADMIN).ativo(true).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, List.of()));
        return id;
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
