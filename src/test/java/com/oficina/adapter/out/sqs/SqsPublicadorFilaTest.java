package com.oficina.adapter.out.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import com.oficina.config.OutboxConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqsPublicadorFilaTest {
    final SqsClient sqs = mock(SqsClient.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final String url = "https://sqs.us-east-1.amazonaws.com/123456789012/notifications.fifo";

    @Test void sendsFrozenContractWithStableDedupAndOrderGroup() throws Exception {
        var event = mapper.readValue(Path.of("contracts/phase3-v1/status-event.json").toFile(), StatusOrdemServicoRegistrado.class);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().messageId("ack").build());
        var publisher = new SqsPublicadorFila(sqs, url, mapper);
        assertThat(publisher.enviar(event)).isEqualTo("ack");
        assertThat(publisher.enviar(event)).isEqualTo("ack");
        var request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(2)).sendMessage(request.capture());
        assertThat(request.getAllValues().get(0)).isEqualTo(request.getAllValues().get(1));
        assertThat(request.getValue().messageDeduplicationId()).isEqualTo(event.eventId().toString());
        assertThat(request.getValue().messageGroupId()).isEqualTo(event.ordemId().toString());
        assertThat(request.getValue().queueUrl()).isEqualTo(url);
        assertThat(request.getValue().messageBody().getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(8192);
        assertThat(mapper.readValue(request.getValue().messageBody(), StatusOrdemServicoRegistrado.class)).isEqualTo(event);
        assertThat(mapper.readTree(request.getValue().messageBody()).get("ocorridoEm").asText()).isEqualTo(event.ocorridoEm().toString());
    }

    @Test void propagatesSendFailureWithoutRetry() throws Exception {
        var event = mapper.readValue(Path.of("contracts/phase3-v1/status-event.json").toFile(), StatusOrdemServicoRegistrado.class);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(SqsException.builder().message("private").build());
        assertThatThrownBy(() -> new SqsPublicadorFila(sqs, url, mapper).enviar(event)).isInstanceOf(SqsException.class);
        verify(sqs).sendMessage(any(SendMessageRequest.class));
    }

    @Test void rejectsOversizedUtf8PayloadBeforeNetwork() throws Exception {
        var event = mapper.readValue(Path.of("contracts/phase3-v1/status-event.json").toFile(), StatusOrdemServicoRegistrado.class);
        var oversized = mock(ObjectMapper.class);
        when(oversized.copy()).thenReturn(oversized);
        when(oversized.registerModule(any())).thenReturn(oversized);
        when(oversized.disable(any(com.fasterxml.jackson.databind.SerializationFeature.class))).thenReturn(oversized);
        when(oversized.writeValueAsString(event)).thenReturn("é".repeat(4097));
        assertThatThrownBy(() -> new SqsPublicadorFila(sqs, url, oversized).enviar(event)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sqs);
    }

    @Test void requiresHttpsFifoQueueAndBoundsSdkToOneTwoSecondAttempt() {
        for (String invalid : new String[] {null, "", "https://example.invalid/standard", "http://example.invalid/a.fifo"})
            assertThatThrownBy(() -> new SqsPublicadorFila(sqs, invalid, mapper)).isInstanceOf(IllegalArgumentException.class);
        var limits = OutboxConfiguration.sdkLimits();
        assertThat(limits.apiCallTimeout()).contains(Duration.ofSeconds(2));
        assertThat(limits.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
        assertThat(limits.retryStrategy().orElseThrow().maxAttempts()).isEqualTo(1);
    }
}
