package com.oficina.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inverse of the local/Kind guard (S-3): under profile {@code aws} the service must talk to real AWS only.
 * Startup fails when an endpoint override leaves {@code *.amazonaws.com}, when static or dummy credentials are
 * present (the pod must use the node role through the default chain) or when the region is outside the Learner Lab.
 * Messages carry property names only, never values.
 */
@Configuration(proxyBeanMethods = false)
@Profile("aws")
public class AwsLearnerLabGuard {
    static final Set<String> REGIOES_LAB = Set.of("us-east-1", "us-west-2");
    static final List<String> ENDPOINTS = List.of("AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_SQS", "AWS_ENDPOINT_URL_STS",
            "AWS_ENDPOINT_URL_DYNAMODB", "SAGA_SQS_ENDPOINT", "oficina.saga.mensageria.endpoint");
    static final List<String> CREDENCIAIS_ESTATICAS = List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN");

    public AwsLearnerLabGuard(Environment env) {
        validar(env);
    }

    static void validar(Environment env) {
        List<String> violacoes = new ArrayList<>();
        for (String nome : ENDPOINTS) {
            String valor = env.getProperty(nome);
            if (presente(valor) && !hostAmazonAws(valor)) violacoes.add(nome + " must be an https *.amazonaws.com endpoint");
        }
        for (String nome : CREDENCIAIS_ESTATICAS) {
            if (presente(env.getProperty(nome))) violacoes.add(nome + " must be unset (use the node role via the default chain)");
        }
        String regiao = env.getProperty("AWS_REGION");
        if (!presente(regiao) || !REGIOES_LAB.contains(regiao.trim())) violacoes.add("AWS_REGION must be one of " + REGIOES_LAB);
        if (!"cadeia-padrao".equals(env.getProperty("oficina.aws.credenciais"))) {
            violacoes.add("oficina.aws.credenciais must be cadeia-padrao");
        }
        if (!violacoes.isEmpty()) {
            throw new IllegalStateException("Profile aws refused to start: " + String.join("; ", violacoes));
        }
    }

    private static boolean presente(String valor) { return valor != null && !valor.isBlank(); }

    private static boolean hostAmazonAws(String valor) {
        try {
            URI uri = URI.create(valor.trim());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && host != null
                    && host.toLowerCase(Locale.ROOT).endsWith(".amazonaws.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
