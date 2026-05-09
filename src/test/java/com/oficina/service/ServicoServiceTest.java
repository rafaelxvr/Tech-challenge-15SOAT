package com.oficina.service;

import com.oficina.dto.ServicoRequest;
import com.oficina.dto.ServicoResponse;
import com.oficina.entity.Servico;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.repository.ServicoRepository;
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
class ServicoServiceTest {

    @Mock
    private ServicoRepository servicoRepository;

    @InjectMocks
    private ServicoService servicoService;

    @Test
    void listar_retornaPagina() {
        Servico s = servicoAtivo();
        when(servicoRepository.findByAtivoTrue(PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(s)));

        Page<ServicoResponse> page = servicoService.listar(PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).nome()).isEqualTo("Revisão");
    }

    @Test
    void buscar_encontrado() {
        UUID id = UUID.randomUUID();
        Servico s = servicoAtivo();
        s.setId(id);
        when(servicoRepository.findById(id)).thenReturn(Optional.of(s));

        ServicoResponse r = servicoService.buscar(id);

        assertThat(r.id()).isEqualTo(id);
    }

    @Test
    void criar_salva() {
        ServicoRequest req = new ServicoRequest(
                "Revisão", "desc", new BigDecimal("100.00"), 60
        );
        when(servicoRepository.save(any(Servico.class))).thenAnswer(inv -> inv.getArgument(0));

        ServicoResponse r = servicoService.criar(req);

        assertThat(r.nome()).isEqualTo("Revisão");
        verify(servicoRepository).save(any(Servico.class));
    }

    @Test
    void desativar_marcaInativo() {
        UUID id = UUID.randomUUID();
        Servico s = servicoAtivo();
        s.setId(id);
        when(servicoRepository.findById(id)).thenReturn(Optional.of(s));

        servicoService.desativar(id);

        assertThat(s.isAtivo()).isFalse();
    }

    @Test
    void obterAtivo_inexistente_lanca() {
        UUID id = UUID.randomUUID();
        when(servicoRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicoService.obterAtivo(id)).isInstanceOf(EntityNotFoundException.class);
    }

    private static Servico servicoAtivo() {
        return Servico.builder()
                .nome("Revisão")
                .descricao("d")
                .valor(new BigDecimal("100.00"))
                .tempoEstimadoMin(60)
                .ativo(true)
                .build();
    }
}
