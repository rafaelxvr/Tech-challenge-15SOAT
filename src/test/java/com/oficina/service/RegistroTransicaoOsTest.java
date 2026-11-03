package com.oficina.service;

import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.domain.identidade.Ator;
import com.oficina.entity.StatusOrdemServico;
import com.oficina.repository.OrdemServicoRepository;
import com.oficina.support.Fixtures;
import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RegistroTransicaoOsTest {
    final OrdemServicoRepository ordens = mock(OrdemServicoRepository.class);
    final NotificacaoPort notificacoes = mock(NotificacaoPort.class);
    final RegistroTransicaoOs registro = new RegistroTransicaoOs(ordens, notificacoes, TokenFixtures.CLOCK, "UTC");

    @Test void prepararDefineZonaERetornaRelogio() {
        var os = Fixtures.ordem(Fixtures.cliente(UUID.randomUUID()), StatusOrdemServico.RECEBIDA);
        assertThat(registro.preparar(os)).isEqualTo(TokenFixtures.NOW);
        assertThat(os.getZonaCompatibilidade()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test void notificarPublicaUltimaTransicaoCanonica() {
        var cliente = Fixtures.cliente(UUID.randomUUID());   // versaoIdentidade defaults to 1
        var os = Fixtures.ordem(cliente, StatusOrdemServico.RECEBIDA);
        os.setId(UUID.randomUUID());
        os.setNumero(10L);
        os.iniciarDiagnostico(Ator.sistema(), TokenFixtures.NOW, null);
        registro.notificar(os);
        verify(ordens).flush();
        var evento = ArgumentCaptor.forClass(StatusOrdemServicoRegistrado.class);
        verify(notificacoes).notificarAtualizacaoStatus(evento.capture());
        assertThat(evento.getValue().statusAnterior()).isEqualTo("RECEBIDA");
        assertThat(evento.getValue().statusNovo()).isEqualTo("EM_DIAGNOSTICO");
        assertThat(evento.getValue().sequencia()).isEqualTo(2);
    }
}
