package com.oficina.saga.adapter.out.messaging;

import com.oficina.saga.application.port.EnviadorMensagens;
import com.oficina.saga.domain.Destino;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class SqsEnviadorMensagens implements EnviadorMensagens {
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
        return sqs.sendMessage(SendMessageRequest.builder().queueUrl(filas.get(destino)).messageBody(corpo)
                .messageGroupId(osId.toString()).messageDeduplicationId(messageId.toString()).build()).messageId();
    }
}
