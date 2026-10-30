package com.oficina.saga.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.oficina.saga.application.mensagem.MensagemInvalidaException;
import com.oficina.saga.application.port.OsDaSagaPort;
import com.oficina.saga.domain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RoteadorRespostasTest {
    static final PrazosSaga PRAZOS = new PrazosSaga(Duration.ofDays(3), Duration.ofMinutes(5),
            Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofMinutes(5), 5);
    static final MomentoSaga M = new MomentoSaga(Instant.parse("2026-09-15T12:00:00Z"), PRAZOS);
    final OsDaSagaPort os = mock(OsDaSagaPort.class);
    final RoteadorRespostas roteador = new RoteadorRespostas(os);

    OsSaga aguardandoPagamento() {
        var s = OsSaga.iniciar(UUID.randomUUID(), M);
        s.diagnosticoConcluido(M);
        s.orcamentoGerado(UUID.randomUUID(), BigDecimal.TEN, M);
        s.registrarDecisao(true, M);
        s.orcamentoAprovado("https://pagamento.example/c/1", M);
        s.retirarComandos(); s.retirarAcoes();
        return s;
    }

    @Test void todaRespostaTemManipulador() {
        assertThat(Arrays.stream(Resposta.values()).filter(r -> !roteador.suporta(r))).isEmpty();
    }

    @Test void orcamentoGeradoLeIdEValorDoPayload() {
        var s = OsSaga.iniciar(UUID.randomUUID(), M);
        s.diagnosticoConcluido(M);
        UUID orcamento = UUID.randomUUID();
        roteador.aplicar(s, Resposta.ORCAMENTO_GERADO, JsonNodeFactory.instance.objectNode()
                .put("orcamentoId", orcamento.toString()).put("total", new BigDecimal("99.90")), M);
        assertThat(s.getOrcamentoId()).isEqualTo(orcamento);
        assertThat(s.getValorOrcado()).isEqualByComparingTo("99.90");
    }

    @Test void payloadIncompletoEInvalido() {
        var s = OsSaga.iniciar(UUID.randomUUID(), M);
        s.diagnosticoConcluido(M);
        assertThatThrownBy(() -> roteador.aplicar(s, Resposta.ORCAMENTO_GERADO,
                JsonNodeFactory.instance.objectNode(), M)).isInstanceOf(MensagemInvalidaException.class);
    }

    @Test void pagamentoConfirmadoReservaPecasEPedeReparo() {
        var s = aguardandoPagamento();
        when(os.reservarPecas(s.getOsId())).thenReturn(true);
        roteador.aplicar(s, Resposta.PAGAMENTO_CONFIRMADO, JsonNodeFactory.instance.objectNode(), M);
        assertThat(s.getEstado()).isEqualTo(EstadoSaga.AGUARDANDO_REPARO);
    }

    @Test void pagamentoConfirmadoSemEstoqueCompensa() {
        var s = aguardandoPagamento();
        when(os.reservarPecas(s.getOsId())).thenReturn(false);
        roteador.aplicar(s, Resposta.PAGAMENTO_CONFIRMADO, JsonNodeFactory.instance.objectNode(), M);
        assertThat(s.getEstado()).isEqualTo(EstadoSaga.COMPENSANDO);
        assertThat(s.getMotivoFalha()).isEqualTo(MotivoFalha.ESTOQUE_INSUFICIENTE);
        assertThat(s.retirarComandos()).extracting(ComandoSaga::comando).containsExactly(Comando.ESTORNAR_PAGAMENTO);
    }
}
