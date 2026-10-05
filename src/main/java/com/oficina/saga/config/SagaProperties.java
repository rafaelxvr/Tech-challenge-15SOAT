package com.oficina.saga.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "oficina.saga")
public record SagaProperties(String filialPadrao, Prazos prazos, Mensageria mensageria) {
    public record Prazos(Duration diagnostico, Duration respostaAutomatica, Duration decisao, Duration pagamento,
                         Duration compensacao, int maxTentativasCompensacao) {}

    public record Mensageria(boolean enabled, String region, String endpoint, String filaBilling,
                             String filaExecucao, String filaRespostas) {}
}
