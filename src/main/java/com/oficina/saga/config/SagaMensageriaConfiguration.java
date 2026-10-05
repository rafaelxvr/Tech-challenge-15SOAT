package com.oficina.saga.config;

import com.oficina.adapter.out.outbox.BackoffPublicacao;
import com.oficina.saga.adapter.in.messaging.SqsRespostasListener;
import com.oficina.saga.adapter.out.messaging.SagaOutboxPublisher;
import com.oficina.saga.adapter.out.messaging.SqsEnviadorMensagens;
import com.oficina.saga.application.CicloOsSagaService;
import com.oficina.saga.application.ProcessadorRespostas;
import com.oficina.saga.application.port.EnviadorMensagens;
import com.oficina.saga.domain.Destino;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "oficina.saga.mensageria.enabled", havingValue = "true")
public class SagaMensageriaConfiguration {
    @Bean(destroyMethod = "close") ClientesSqsSaga clientesSqsSaga(SagaProperties propriedades,
            @Value("${AWS_ROLE_ARN:}") String roleArn, @Value("${AWS_WEB_IDENTITY_TOKEN_FILE:}") String tokenFile) {
        return ClientesSqsSaga.criar(propriedades.mensageria(), roleArn, tokenFile);
    }

    @Bean EnviadorMensagens enviadorMensagens(ClientesSqsSaga clientes, SagaProperties propriedades) {
        var m = propriedades.mensageria();
        return new SqsEnviadorMensagens(clientes.envio(),
                Map.of(Destino.BILLING, m.filaBilling(), Destino.EXECUCAO, m.filaExecucao()));
    }

    @Bean SagaOutboxPublisher sagaOutboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                                  EnviadorMensagens enviador, Clock clock) {
        return new SagaOutboxPublisher(jdbc, new TransactionTemplate(manager), enviador, clock, BackoffPublicacao.aleatorio());
    }

    @Bean PollerPeriodico sagaOutboxPoller(SagaOutboxPublisher publisher) {
        return new PollerPeriodico("saga-outbox", () -> {
            for (int i = 0; i < 20 && publisher.publicarProximo(); i++) { /* drain up to 20 per tick */ }
        }, Duration.ofSeconds(1));
    }

    @Bean PollerPeriodico sagaPrazosPoller(CicloOsSagaService ciclo) {
        return new PollerPeriodico("saga-prazos", () -> ciclo.expirarPrazosVencidos(20), Duration.ofSeconds(15));
    }

    @Bean SqsRespostasListener sqsRespostasListener(ClientesSqsSaga clientes, SagaProperties propriedades,
                                                    ProcessadorRespostas processador) {
        SqsEnviadorMensagens.validarFila(propriedades.mensageria().filaRespostas());
        return new SqsRespostasListener(clientes.recepcao(), propriedades.mensageria().filaRespostas(), processador);
    }
}
