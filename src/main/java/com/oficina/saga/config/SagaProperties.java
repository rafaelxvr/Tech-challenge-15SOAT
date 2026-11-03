package com.oficina.saga.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code enabled} is the saga switch: off, new orders keep the Phase 3 manual flow (see {@link SagaConfiguration}). */
@ConfigurationProperties(prefix = "oficina.saga")
public record SagaProperties(String filialPadrao, Prazos prazos, Mensageria mensageria, boolean enabled) {
    public record Prazos(Duration diagnostico, Duration respostaAutomatica, Duration decisao, Duration pagamento,
                         Duration compensacao, int maxTentativasCompensacao) {}

    public record Mensageria(boolean enabled, String region, String endpoint, String filaBilling,
                             String filaExecucao, String filaRespostas) {}
}
