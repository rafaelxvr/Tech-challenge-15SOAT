package com.oficina.saga.application.mensagem;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapeadorMensagensTest {
    final MapeadorMensagens mapeador = new MapeadorMensagens();

    Envelope resposta(UUID causa) {
        return new Envelope(UUID.randomUUID(), Envelope.Kind.REPLY, "OrcamentoGerado", 1, UUID.randomUUID(),
                UUID.randomUUID(), 2, causa, Instant.parse("2026-09-15T12:00:00Z"), UUID.randomUUID().toString(), null,
                JsonNodeFactory.instance.objectNode().put("total", 10));
    }

    @Test void idaEVoltaPreservaCampos() {
        var original = resposta(UUID.randomUUID());
        assertThat(mapeador.ler(mapeador.escrever(original))).isEqualTo(original);
    }

    @Test void camposDesconhecidosSaoIgnorados() {
        String corpo = mapeador.escrever(resposta(UUID.randomUUID())).replaceFirst("\\{", "{\"novoCampo\":1,");
        assertThat(mapeador.ler(corpo).type()).isEqualTo("OrcamentoGerado");
    }

    @Test void respostaSemCausaEJsonInvalidoSaoRejeitados() {
        assertThatThrownBy(() -> resposta(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapeador.ler("{nao-json")).isInstanceOf(MensagemInvalidaException.class);
        assertThatThrownBy(() -> mapeador.ler("{\"type\":\"X\"}")).isInstanceOf(MensagemInvalidaException.class);
    }
}
