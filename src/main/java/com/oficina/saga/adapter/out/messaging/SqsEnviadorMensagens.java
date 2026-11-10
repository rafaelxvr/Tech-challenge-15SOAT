package com.oficina.saga.adapter.out.messaging;

import com.oficina.config.CorrelationFilter;
import com.oficina.saga.application.port.EnviadorMensagens;
import com.oficina.saga.domain.Destino;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import com.oficina.saga.application.rastreio.CarrierRastreio;

import java.util.EnumMap;
import java.util.LinkedHashMap;
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
    public String enviar(Destino destino, UUID osId, UUID messageId, String corpo, Map<String, String> traco) {
        var requisicao = SendMessageRequest.builder().queueUrl(filas.get(destino)).messageBody(corpo)
                .messageGroupId(osId.toString()).messageDeduplicationId(messageId.toString());
        // Persisted carrier first (captured in the producing transaction); the validated MDC traceparent is the fallback.
        Map<String, String> carrier = new LinkedHashMap<>(CarrierRastreio.sanitizar(traco));
        String traceparent = CorrelationFilter.traceparent();
        if (carrier.isEmpty() && traceparent != null) carrier.put(ATRIBUTO_TRACEPARENT, traceparent);
        if (!carrier.isEmpty()) {
            Map<String, MessageAttributeValue> atributos = new LinkedHashMap<>();
            carrier.forEach((nome, valor) -> atributos.put(nome,
                    MessageAttributeValue.builder().dataType("String").stringValue(valor).build()));
            requisicao.messageAttributes(atributos);
        }
        return sqs.sendMessage(requisicao.build()).messageId();
    }
}
