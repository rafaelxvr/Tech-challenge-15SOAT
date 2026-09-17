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

    @Test void sdkAndAdaptersConstructWithoutAwsCalls(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var config = new OutboxConfiguration();
        var token = java.nio.file.Files.writeString(directory.resolve("token"), "synthetic-token");
        try (var http = config.outboxHttpClient();
             var sts = config.outboxStsClient("us-east-1", http);
             var credentials = config.outboxIrsaCredentials(sts, "arn:aws:iam::123456789012:role/synthetic", token.toString());
             var client = config.outboxSqsClient("us-east-1", credentials, http)) {
            assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout()).contains(java.time.Duration.ofSeconds(2));
            var stsLimits = sts.serviceClientConfiguration().overrideConfiguration();
            assertThat(stsLimits.apiCallTimeout()).contains(java.time.Duration.ofSeconds(2));
            assertThat(stsLimits.apiCallAttemptTimeout()).contains(java.time.Duration.ofSeconds(2));
            assertThat(stsLimits.retryStrategy().orElseThrow().maxAttempts()).isEqualTo(1);
            assertThat(config.publicadorFila(client, new ObjectMapper(), "https://sqs.us-east-1.amazonaws.com/123456789012/events.fifo")).isNotNull();
        }
        assertThat(config.outboxPublisher(mock(JdbcTemplate.class), mock(PlatformTransactionManager.class),
                mock(PublicadorFila.class), Clock.systemUTC())).isNotNull();
    }
}
