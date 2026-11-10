package com.oficina.saga.adapter.in.messaging;

import com.oficina.saga.application.ProcessadorRespostas;
import com.oficina.saga.application.rastreio.PropagadorTraco;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqsRespostasListenerTest {
    final SqsClient sqs = mock(SqsClient.class);
    final ProcessadorRespostas processador = mock(ProcessadorRespostas.class);
    final PropagadorTraco propagador = mock(PropagadorTraco.class);
    final SqsRespostasListener listener = new SqsRespostasListener(sqs, "https://sqs.local/000/os-saga-replies.fifo", processador, propagador);

    static Message mensagem(String corpo, String grupo, String recibo) {
        return Message.builder().body(corpo).receiptHandle(recibo)
                .attributes(Map.of(MessageSystemAttributeName.MESSAGE_GROUP_ID, grupo)).build();
    }

    @Test void processaEApagaCadaMensagem() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(mensagem("a", "os-1", "r1"), mensagem("b", "os-2", "r2")).build());
        assertThat(listener.receberUmLote()).isEqualTo(2);
        verify(processador).processar("a");
        verify(processador).processar("b");
        verify(sqs, times(2)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test void falhaNaoDeixaMensagemPosteriorDoMesmoGrupoPassar() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(mensagem("falha", "os-1", "r1"), mensagem("depois", "os-1", "r2"),
                        mensagem("outra", "os-2", "r3")).build());
        doThrow(new IllegalStateException("x")).when(processador).processar("falha");
        assertThat(listener.receberUmLote()).isEqualTo(1);
        verify(processador, never()).processar("depois");
        verify(processador).processar("outra");
        verify(sqs, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test void mensagensSemGrupoCompartilhamUmaChaveDeRetencao() {
        Message semGrupo1 = Message.builder().body("falha").receiptHandle("r1").build();
        Message semGrupo2 = Message.builder().body("depois").receiptHandle("r2").build();
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(semGrupo1, semGrupo2).build());
        doThrow(new IllegalStateException("x")).when(processador).processar("falha");
        assertThat(listener.receberUmLote()).isZero();
        verify(processador, never()).processar("depois");
    }

    @Test void pedeAtributoDeGrupoELongPolling() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().build());
        listener.receberUmLote();
        verify(sqs).receiveMessage(argThat((ReceiveMessageRequest r) -> r.waitTimeSeconds() == 10
                && r.maxNumberOfMessages() == 10
                && r.messageSystemAttributeNames().contains(MessageSystemAttributeName.MESSAGE_GROUP_ID)));
    }

    @Test void pedeOsAtributosDeRastreioDaMensagem() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().build());
        listener.receberUmLote();
        verify(sqs).receiveMessage(argThat((ReceiveMessageRequest r) ->
                r.messageAttributeNames().containsAll(java.util.List.of("traceparent", "tracestate", "newrelic"))));
    }

    @Test void aceitaOsHeadersDeRastreioAntesDaLogicaDeNegocio() {
        var traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        var msg = Message.builder().body("a").receiptHandle("r1")
                .attributes(Map.of(MessageSystemAttributeName.MESSAGE_GROUP_ID, "os-1"))
                .messageAttributes(Map.of("traceparent", MessageAttributeValue.builder().dataType("String").stringValue(traceparent).build()))
                .build();
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().messages(msg).build());
        listener.receberUmLote();
        var ordem = inOrder(propagador, processador);
        ordem.verify(propagador).aceitar(Map.of("traceparent", traceparent));
        ordem.verify(processador).processar("a");
    }

    @Test void processarNaTransacaoAbreTransacaoDispatcher() throws NoSuchMethodException {
        var trace = SqsRespostasListener.class.getDeclaredMethod("processarNaTransacao", Message.class)
                .getAnnotation(com.newrelic.api.agent.Trace.class);
        assertThat(trace).isNotNull();
        assertThat(trace.dispatcher()).isTrue();
    }
}
