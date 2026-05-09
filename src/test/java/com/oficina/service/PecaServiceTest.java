package com.oficina.service;

import com.oficina.dto.PecaRequest;
import com.oficina.dto.PecaResponse;
import com.oficina.entity.Peca;
import com.oficina.exception.DuplicateEntityException;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.repository.PecaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PecaServiceTest {

    @Mock
    private PecaRepository pecaRepository;

    @InjectMocks
    private PecaService pecaService;

    @Test
    void listar_retornaAtivas() {
        Peca p = pecaAtiva();
        when(pecaRepository.findByAtivoTrue(PageRequest.of(0, 5)))
                .thenReturn(new PageImpl<>(List.of(p)));

        Page<PecaResponse> page = pecaService.listar(PageRequest.of(0, 5));

        assertThat(page.getContent().get(0).codigo()).isEqualTo("P-1");
    }

    @Test
    void criar_codigoDuplicado_lanca() {
        PecaRequest req = requestPadrao();
        when(pecaRepository.findByCodigo("P-1")).thenReturn(Optional.of(pecaAtiva()));

        assertThatThrownBy(() -> pecaService.criar(req)).isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void atualizar_outroCodigoExistente_lanca() {
        UUID id = UUID.randomUUID();
        UUID outroId = UUID.randomUUID();
        Peca existente = pecaAtiva();
        existente.setId(id);
        PecaRequest req = new PecaRequest(
                "P-2", "Nome", "desc",
                new BigDecimal("10.00"), 5, 1, "UN"
        );

        when(pecaRepository.findById(id)).thenReturn(Optional.of(existente));
        Peca outra = pecaAtiva();
        outra.setId(outroId);
        outra.setCodigo("P-2");
        when(pecaRepository.findByCodigo("P-2")).thenReturn(Optional.of(outra));

        assertThatThrownBy(() -> pecaService.atualizar(id, req)).isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void obterAtiva_retorna() {
        UUID id = UUID.randomUUID();
        Peca p = pecaAtiva();
        p.setId(id);
        when(pecaRepository.findById(id)).thenReturn(Optional.of(p));

        assertThat(pecaService.obterAtiva(id).getCodigo()).isEqualTo("P-1");
    }

    @Test
    void desativar_defineAtivoFalse() {
        UUID id = UUID.randomUUID();
        Peca p = pecaAtiva();
        p.setId(id);
        when(pecaRepository.findById(id)).thenReturn(Optional.of(p));

        pecaService.desativar(id);

        assertThat(p.isAtivo()).isFalse();
        verify(pecaRepository).findById(id);
    }

    private static PecaRequest requestPadrao() {
        return new PecaRequest(
                "P-1", "Filtro", "desc",
                new BigDecimal("10.00"), 5, 1, "UN"
        );
    }

    private static Peca pecaAtiva() {
        return Peca.builder()
                .codigo("P-1")
                .nome("Filtro")
                .valorUnitario(new BigDecimal("10.00"))
                .quantidadeEstoque(10)
                .quantidadeMinima(1)
                .unidadeMedida("UN")
                .ativo(true)
                .build();
    }
}
