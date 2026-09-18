package com.oficina.adapter.out.sqs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.oficina.application.notificacao.PublicadorFila;
import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.nio.charset.StandardCharsets;

public class SqsPublicadorFila implements PublicadorFila {
    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper mapper;

    public SqsPublicadorFila(SqsClient sqs, String queueUrl, ObjectMapper mapper) {
        if (queueUrl == null || !queueUrl.startsWith("https://") || !queueUrl.endsWith(".fifo"))
            throw new IllegalArgumentException("HTTPS FIFO queue URL required");
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.mapper = mapper.copy().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override public String enviar(StatusOrdemServicoRegistrado evento) {
        final String payload;
        try { payload = mapper.writeValueAsString(evento); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Invalid event payload"); }
        if (payload.getBytes(StandardCharsets.UTF_8).length > StatusOrdemServicoRegistrado.MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Event payload exceeds limit");
        return sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl)
                .messageGroupId(evento.ordemId().toString()).messageDeduplicationId(evento.eventId().toString())
                .messageBody(payload).build()).messageId();
    }
}
