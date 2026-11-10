package com.oficina.saga.adapter.out.messaging;

import com.oficina.saga.domain.Destino;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsEnviadorMensagensTest {
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    final SqsClient sqs = mock(SqsClient.class);
    final SqsEnviadorMensagens enviador = new SqsEnviadorMensagens(sqs, filas());

    static Map<Destino, String> filas() {
        Map<Destino, String> filas = new EnumMap<>(Destino.class);
        for (Destino d : Destino.values()) filas.put(d, "https://sqs.local/000/" + d.name().toLowerCase() + ".fifo");
        return filas;
    }

    @AfterEach void limpar() { MDC.remove("traceparent"); }

    Map<String, String> traco = Map.of();

    private SendMessageRequest enviar() {
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().messageId("m1").build());
        assertThat(enviador.enviar(Destino.values()[0], UUID.randomUUID(), UUID.randomUUID(), "{}", traco)).isEqualTo("m1");
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Test void propagaTraceparentValidoComoAtributoDaMensagem() {
        MDC.put("traceparent", TRACEPARENT);
        var attr = enviar().messageAttributes().get(SqsEnviadorMensagens.ATRIBUTO_TRACEPARENT);
        assertThat(attr.dataType()).isEqualTo("String");
        assertThat(attr.stringValue()).isEqualTo(TRACEPARENT);
    }

    @Test void naoEnviaAtributoQuandoTraceparentAusente() {
        assertThat(enviar().messageAttributes()).isEmpty();
    }

    @Test void descartaTraceparentMalformadoSemPropagarValorNaoConfiavel() {
        MDC.put("traceparent", "00-malformado\r\nX-Injetado: 1");
        assertThat(enviar().messageAttributes()).isEmpty();
    }

    @Test void enviaCarrierPersistidoComTraceparentTracestateENewrelic() {
        traco = Map.of("traceparent", TRACEPARENT, "tracestate", "123@nr=0-0-1-2-3", "newrelic", "eyJ2IjpbMCwxXX0=");
        var atributos = enviar().messageAttributes();
        assertThat(atributos).containsOnlyKeys("traceparent", "tracestate", "newrelic");
        assertThat(atributos.get("tracestate").stringValue()).isEqualTo("123@nr=0-0-1-2-3");
        assertThat(atributos.get("newrelic").dataType()).isEqualTo("String");
    }

    @Test void carrierPersistidoTemPrioridadeSobreOMdcEDescartaCabecalhosDesconhecidosOuInseguros() {
        MDC.put("traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        traco = Map.of("traceparent", TRACEPARENT, "x-pii", "cliente@exemplo.com", "tracestate", "a\r\nb");
        var atributos = enviar().messageAttributes();
        assertThat(atributos).containsOnlyKeys("traceparent");
        assertThat(atributos.get("traceparent").stringValue()).isEqualTo(TRACEPARENT);
    }
}
