package com.oficina.service;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.domain.identidade.TipoAtor;
import com.oficina.dto.*;
import com.oficina.entity.*;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.exception.BusinessRuleException;
import com.oficina.repository.OrdemServicoRepository;
import com.oficina.security.*;
import com.oficina.support.Fixtures;
import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.oficina.dto.DecisaoOrcamentoRequest.DecisaoOrcamento.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerOrderDecisionTest {
    final OrdemServicoRepository orders = mock(OrdemServicoRepository.class);
    final NotificacaoPort notifications = mock(NotificacaoPort.class);
    final OrdemServicoService service = new OrdemServicoService(orders, mock(ClienteService.class),
            mock(VeiculoService.class), mock(ServicoService.class), mock(PecaService.class), notifications, TokenFixtures.CLOCK);
    final UUID ownerId = UUID.randomUUID();
    final IdentidadeAutenticada owner = identity(TipoPrincipal.CUSTOMER, ownerId,
            "SCOPE_orders:read:self", "SCOPE_orders:decide:self");
    OrdemServico order;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "zonaCompatibilidade", "UTC");
        order = Fixtures.ordem(Fixtures.cliente(ownerId), StatusOrdemServico.AGUARDANDO_APROVACAO);
        order.setNumero(77L);
    }

    static IdentidadeAutenticada identity(TipoPrincipal type, UUID id, String... scopes) {
        return new IdentidadeAutenticada(type, id, Set.of(scopes), 1);
    }

    @Test void directCallsRequireCustomerAndExactScopeBeforeAnyLookup() {
        for (IdentidadeAutenticada identity : java.util.Arrays.asList(null,
                identity(TipoPrincipal.STAFF, ownerId, "SCOPE_orders:read:self", "SCOPE_orders:decide:self"),
                identity(TipoPrincipal.CUSTOMER, ownerId, "orders:read:self", "orders:decide:self"))) {
            assertThatThrownBy(() -> service.acompanhamentoDoCliente(77L, identity)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.decidirComoCliente(77L, identity, new DecisaoClienteRequest(APROVADO, null)))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(orders, notifications);
    }

    @Test void readAndDecisionScopesAreNotInterchangeable() {
        assertThatThrownBy(() -> service.acompanhamentoDoCliente(77L,
                identity(TipoPrincipal.CUSTOMER, ownerId, "SCOPE_orders:decide:self")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.decidirComoCliente(77L,
                identity(TipoPrincipal.CUSTOMER, ownerId, "SCOPE_orders:read:self"), new DecisaoClienteRequest(APROVADO, null)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(orders, notifications);
    }

    @Test void wrongOwnerAndMissingOrderHaveIdenticalErrorAndCannotMutate() {
        when(orders.findComPecasEClientePorNumero(77L)).thenReturn(Optional.of(order));
        var stranger = identity(TipoPrincipal.CUSTOMER, UUID.randomUUID(), "SCOPE_orders:decide:self");
        Throwable wrongOwner = catchThrowable(() -> service.decidirComoCliente(77L, stranger, new DecisaoClienteRequest(APROVADO, null)));
        when(orders.findComPecasEClientePorNumero(77L)).thenReturn(Optional.empty());
        Throwable missing = catchThrowable(() -> service.decidirComoCliente(77L, owner, new DecisaoClienteRequest(APROVADO, null)));
        assertThat(wrongOwner).isInstanceOf(EntityNotFoundException.class).hasMessage(missing.getMessage());
        assertThat(order.getStatus()).isEqualTo(StatusOrdemServico.AGUARDANDO_APROVACAO);
        assertThat(order.getHistorico()).hasSize(3);
        verify(orders, never()).save(any());
        verifyNoInteractions(notifications);
    }

    @Test void legacyDocumentCannotSelectAnotherCustomerOrBypassOwnership() {
        when(orders.findComPecasEClientePorNumero(77L)).thenReturn(Optional.of(order));
        var stranger = identity(TipoPrincipal.CUSTOMER, UUID.randomUUID(), "SCOPE_orders:decide:self");
        assertThatThrownBy(() -> service.decidirComoClienteLegado(77L, stranger,
                new DecisaoOrcamentoRequest(APROVADO, order.getCliente().getDocumento(), null)))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.decidirComoClienteLegado(77L, owner,
                new DecisaoOrcamentoRequest(APROVADO, "52998224725", null))).isInstanceOf(BusinessRuleException.class);
        verify(orders, never()).save(any());
        verifyNoInteractions(notifications);
    }

    @Test void canonicalApprovalRecordsCustomerAndPreservesTheirNoteInternally() {
        when(orders.findComPecasEClientePorNumero(77L)).thenReturn(Optional.of(order));
        when(orders.save(order)).thenReturn(order);
        var response = service.decidirComoCliente(77L, owner, new DecisaoClienteRequest(APROVADO, "Autorizo orçamento"));
        var history = order.getHistorico().get(3);
        assertThat(response.status()).isEqualTo(StatusOrdemServico.EM_EXECUCAO);
        assertThat(history.getAtorTipo()).isEqualTo(TipoAtor.CUSTOMER);
        assertThat(history.getAtorClienteId()).isEqualTo(ownerId);
        assertThat(history.getAlteradoPor()).isNull();
        assertThat(history.getObservacao()).isEqualTo("Autorizo orçamento");
        assertThat(history.getOcorridoEm()).isEqualTo(TokenFixtures.NOW);
    }

    @Test void refusalReturnsToDiagnosisWithoutTouchingStock() {
        when(orders.findComPecasEClientePorNumero(77L)).thenReturn(Optional.of(order));
        when(orders.save(order)).thenReturn(order);
        assertThat(service.decidirComoCliente(77L, owner, new DecisaoClienteRequest(RECUSADO, null)).status())
                .isEqualTo(StatusOrdemServico.EM_DIAGNOSTICO);
        assertThat(order.getHistorico().get(3).getAtorClienteId()).isEqualTo(ownerId);
        assertThat(order.getHistorico().get(3).getObservacao()).isEqualTo("Orçamento recusado pelo cliente");
    }
}
