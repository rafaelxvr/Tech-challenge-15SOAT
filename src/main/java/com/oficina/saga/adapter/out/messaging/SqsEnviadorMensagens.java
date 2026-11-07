package com.oficina.saga.adapter.out.messaging;

import com.oficina.config.CorrelationFilter;
import com.oficina.saga.application.port.EnviadorMensagens;
import com.oficina.saga.domain.Destino;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class SqsEnviadorMensagens implements EnviadorMensagens {
    /** Message attribute carrying the W3C trace context so consumers can join the trace without parsing the body. */
    public static final String ATRIBUTO_TRACEPARENT = "traceparent";
    private final SqsClient sqs;
    private final Map<Destino, String> filas;

    public SqsEnviadorMensagens(SqsClient sqs, Map<Destino, String> filas) {
        for (Destino destino : Destino.values()) validarFila(filas.get(destino));
        this.sqs = sqs;
        this.filas = new EnumMap<>(filas);
    }

    public static void validarFila(String url) {
        if (url == null || url.isBlank() || !url.endsWith(".fifo")) throw new IllegalArgumentException("FIFO queue URL required");
    }

    @Override
    public String enviar(Destino destino, UUID osId, UUID messageId, String corpo) {
        var requisicao = SendMessageRequest.builder().queueUrl(filas.get(destino)).messageBody(corpo)
                .messageGroupId(osId.toString()).messageDeduplicationId(messageId.toString());
        // Only a validated W3C traceparent is propagated; absent or malformed contexts send no attribute.
        String traceparent = CorrelationFilter.traceparent();
        if (traceparent != null) {
            requisicao.messageAttributes(Map.of(ATRIBUTO_TRACEPARENT,
                    MessageAttributeValue.builder().dataType("String").stringValue(traceparent).build()));
        }
        return sqs.sendMessage(requisicao.build()).messageId();
    }
}
