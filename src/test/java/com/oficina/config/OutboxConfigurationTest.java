package com.oficina.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.adapter.out.outbox.OutboxPublisher;
import com.oficina.application.notificacao.PublicadorFila;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxConfigurationTest {
    @Test void createsOneLifecyclePollerAndStopsItWithoutSendingAtStartup() {
        var publisher = mock(OutboxPublisher.class);
        var poller = new OutboxConfiguration().outboxPoller(publisher);
        assertThat(poller.isRunning()).isFalse();
        poller.stop();
        try {
            poller.start(); poller.start();
            assertThat(poller.isRunning()).isTrue();
            verifyNoInteractions(publisher);
        } finally { poller.stop(); }
        assertThat(poller.isRunning()).isFalse();
    }

    @Test void sdkAndAdaptersConstructWithoutAwsCalls() {
        var config = new OutboxConfiguration();
        try (var client = config.outboxSqsClient("us-east-1")) {
            assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout()).contains(java.time.Duration.ofSeconds(2));
            assertThat(config.publicadorFila(client, new ObjectMapper(), "https://sqs.us-east-1.amazonaws.com/123456789012/events.fifo")).isNotNull();
        }
        assertThat(config.outboxPublisher(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class),
                mock(PublicadorFila.class), Clock.systemUTC())).isNotNull();
        // IRSA's reflective credentials provider needs this SDK module at runtime.
        assertThat(software.amazon.awssdk.services.sts.StsClient.class).isNotNull();
    }
}
