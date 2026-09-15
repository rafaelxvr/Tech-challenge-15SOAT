package com.oficina.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.adapter.out.outbox.OutboxPublisher;
import com.oficina.adapter.out.sqs.SqsPublicadorFila;
import com.oficina.application.notificacao.PublicadorFila;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "oficina.outbox.enabled", havingValue = "true")
public class OutboxConfiguration {
    public static ClientOverrideConfiguration sdkLimits() {
        return ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(2))
                .apiCallAttemptTimeout(Duration.ofSeconds(2))
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()).build();
    }

    @Bean(destroyMethod = "close") SqsClient outboxSqsClient(@Value("${oficina.outbox.region}") String region) {
        return SqsClient.builder().region(Region.of(region)).overrideConfiguration(sdkLimits())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                        .socketTimeout(Duration.ofSeconds(2))).build();
    }

    @Bean PublicadorFila publicadorFila(SqsClient outboxSqsClient, ObjectMapper mapper,
                                       @Value("${oficina.outbox.queue-url}") String queueUrl) {
        return new SqsPublicadorFila(outboxSqsClient, queueUrl, mapper);
    }

    @Bean OutboxPublisher outboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                         PublicadorFila queue, Clock clock) {
        return new OutboxPublisher(jdbc, new TransactionTemplate(manager), queue, clock);
    }

    @Bean Poller outboxPoller(OutboxPublisher publisher) { return new Poller(publisher); }

    /** One event per tick, one thread per pod, five seconds idle between attempts. No cron framework. */
    static final class Poller implements SmartLifecycle {
        private final OutboxPublisher publisher;
        private ScheduledExecutorService executor;
        Poller(OutboxPublisher publisher) { this.publisher = publisher; }
        public synchronized void start() {
            if (isRunning()) return;
            executor = Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "outbox-publisher"); thread.setDaemon(true); return thread;
            });
            executor.scheduleWithFixedDelay(publisher::publicarProximo, 5, 5, TimeUnit.SECONDS);
        }
        public synchronized void stop() {
            if (executor != null) executor.shutdownNow();
        }
        public synchronized boolean isRunning() { return executor != null && !executor.isShutdown(); }
    }
}
