package com.oficina.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inverse of the local/Kind guard (S-3): under profile {@code aws} the service must talk to real AWS only and must
 * obtain credentials from the node role (IMDS), the last link of the SDK {@code DefaultCredentialsProvider} chain.
 * Startup fails when:
 * <ul>
 *   <li>any endpoint override (env var, JVM system property or shared config {@code endpoint_url}) leaves
 *       {@code https://*.amazonaws.com}, including the IMDS endpoint override;</li>
 *   <li>any credential source that the chain consults BEFORE the IMDS is configured: static keys (env or JVM),
 *       {@code AWS_PROFILE}/{@code aws.profile}, container credentials, web identity, or a shared credentials/config
 *       file with credential material;</li>
 *   <li>IMDS is disabled, or the region is outside the Learner Lab.</li>
 * </ul>
 * Messages carry property names only, never values.
 */
@Configuration(proxyBeanMethods = false)
@Profile("aws")
public class AwsLearnerLabGuard {
    static final Set<String> REGIOES_LAB = Set.of("us-east-1", "us-west-2");

    /** Endpoint overrides that are validated as https *.amazonaws.com when present (env var, JVM property, Spring property). */
    static final List<String> ENDPOINTS = List.of("AWS_ENDPOINT_URL", "aws.endpointUrl", "SAGA_SQS_ENDPOINT",
            "oficina.saga.mensageria.endpoint");
    /** Any property whose name starts with one of these (case-insensitive) is an endpoint override (per-service ones included). */
    static final List<String> PREFIXOS_ENDPOINT = List.of("AWS_ENDPOINT_URL_", "aws.endpointUrl");
    /** IMDS endpoint override: the node role is only trustworthy through the real link-local IMDS. */
    static final List<String> ENDPOINTS_IMDS = List.of("AWS_EC2_METADATA_SERVICE_ENDPOINT", "aws.ec2MetadataServiceEndpoint");

    /** Credential sources consulted by DefaultCredentialsProvider before the IMDS (must all be unset). */
    static final List<String> CREDENCIAIS_ANTERIORES_AO_IMDS = List.of(
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "aws.accessKeyId", "aws.secretAccessKey", "aws.sessionToken",
            "AWS_PROFILE", "aws.profile",
            "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "AWS_CONTAINER_CREDENTIALS_FULL_URI",
            "AWS_CONTAINER_AUTHORIZATION_TOKEN", "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
            "AWS_WEB_IDENTITY_TOKEN_FILE", "aws.webIdentityTokenFile", "AWS_ROLE_ARN", "aws.roleArn");

    /** Keys in a shared config/credentials file that point away from the node role or the real AWS endpoints. */
    static final List<String> CHAVES_ARQUIVO_COMPARTILHADO = List.of("aws_access_key_id", "aws_secret_access_key",
            "aws_session_token", "credential_process", "credential_source", "web_identity_token_file", "role_arn",
            "source_profile", "sso_session", "sso_start_url", "sso_account_id", "login_session");

    public AwsLearnerLabGuard(Environment env) {
        validar(env, Path.of(System.getProperty("user.home", "")));
    }

    static void validar(Environment env) {
        validar(env, Path.of(System.getProperty("user.home", "")));
    }

    static void validar(Environment env, Path home) {
        List<String> violacoes = new ArrayList<>();
        validarEndpoints(env, violacoes);
        validarCredenciais(env, violacoes);
        validarImds(env, violacoes);
        validarArquivosCompartilhados(env, home, violacoes);
        validarRegiao(env, violacoes);
        if (!"cadeia-padrao".equals(env.getProperty("oficina.aws.credenciais"))) {
            violacoes.add("oficina.aws.credenciais must be cadeia-padrao");
        }
        if (!violacoes.isEmpty()) {
            throw new IllegalStateException("Profile aws refused to start: " + String.join("; ", violacoes));
        }
    }

    private static void validarEndpoints(Environment env, List<String> violacoes) {
        Set<String> nomes = new LinkedHashSet<>(ENDPOINTS);
        nomes.addAll(nomesComPrefixo(env, PREFIXOS_ENDPOINT));
        for (String nome : nomes) {
            String valor = env.getProperty(nome);
            if (presente(valor) && !hostAmazonAws(valor)) violacoes.add(nome + " must be an https *.amazonaws.com endpoint");
        }
        for (String nome : ENDPOINTS_IMDS) {
            if (presente(env.getProperty(nome))) violacoes.add(nome + " must be unset (the IMDS endpoint cannot be overridden)");
        }
    }

    private static void validarCredenciais(Environment env, List<String> violacoes) {
        for (String nome : CREDENCIAIS_ANTERIORES_AO_IMDS) {
            if (presente(env.getProperty(nome))) violacoes.add(nome + " must be unset (use the node role via the default chain)");
        }
    }

    private static void validarImds(Environment env, List<String> violacoes) {
        for (String nome : List.of("AWS_EC2_METADATA_DISABLED", "aws.disableEc2Metadata")) {
            if ("true".equalsIgnoreCase(trim(env.getProperty(nome)))) violacoes.add(nome + " must not be true (the node role comes from the IMDS)");
        }
    }

    private static void validarRegiao(Environment env, List<String> violacoes) {
        String regiao = env.getProperty("AWS_REGION");
        if (!presente(regiao) || !REGIOES_LAB.contains(regiao.trim())) violacoes.add("AWS_REGION must be one of " + REGIOES_LAB);
        for (String nome : List.of("aws.region", "AWS_DEFAULT_REGION")) {
            String outra = env.getProperty(nome);
            if (presente(outra) && !REGIOES_LAB.contains(outra.trim())) violacoes.add(nome + " must be one of " + REGIOES_LAB);
        }
    }

    /** Shared credentials/config files are read by the profile provider (before IMDS) and may carry endpoint_url. */
    private static void validarArquivosCompartilhados(Environment env, Path home, List<String> violacoes) {
        Path credenciais = caminho(env, home, List.of("AWS_SHARED_CREDENTIALS_FILE", "aws.sharedCredentialsFile"), ".aws/credentials");
        Path config = caminho(env, home, List.of("AWS_CONFIG_FILE", "aws.configFile"), ".aws/config");
        for (Path arquivo : List.of(credenciais, config)) {
            for (String achado : inspecionar(arquivo)) violacoes.add(achado);
        }
    }

    private static Path caminho(Environment env, Path home, List<String> nomes, String padrao) {
        for (String nome : nomes) {
            String valor = env.getProperty(nome);
            if (presente(valor)) return Path.of(valor.trim());
        }
        return home.resolve(padrao);
    }

    private static List<String> inspecionar(Path arquivo) {
        List<String> achados = new ArrayList<>();
        if (!Files.isRegularFile(arquivo)) return achados;
        List<String> linhas;
        try {
            linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            achados.add("shared AWS file " + arquivo.getFileName() + " is unreadable and must be removed");
            return achados;
        }
        Set<String> chaves = new LinkedHashSet<>();
        for (String linha : linhas) {
            String texto = linha.strip();
            int igual = texto.indexOf('=');
            if (texto.isEmpty() || texto.startsWith("#") || texto.startsWith(";") || igual < 0) continue;
            String chave = texto.substring(0, igual).strip().toLowerCase(Locale.ROOT);
            String valor = texto.substring(igual + 1).strip();
            if (CHAVES_ARQUIVO_COMPARTILHADO.contains(chave)) chaves.add(chave);
            else if (chave.equals("endpoint_url") && !hostAmazonAws(valor)) chaves.add(chave);
        }
        for (String chave : chaves) {
            achados.add("shared AWS file " + arquivo.getFileName() + " must not set " + chave + " (use the node role)");
        }
        return achados;
    }

    private static Set<String> nomesComPrefixo(Environment env, List<String> prefixos) {
        Set<String> nomes = new LinkedHashSet<>();
        if (!(env instanceof ConfigurableEnvironment configuravel)) return nomes;
        for (PropertySource<?> fonte : configuravel.getPropertySources()) {
            if (!(fonte instanceof EnumerablePropertySource<?> enumeravel)) continue;
            for (String nome : enumeravel.getPropertyNames()) {
                String minusculo = nome.toLowerCase(Locale.ROOT);
                for (String prefixo : prefixos) {
                    if (minusculo.startsWith(prefixo.toLowerCase(Locale.ROOT))) nomes.add(nome);
                }
            }
        }
        return nomes;
    }

    private static String trim(String valor) { return valor == null ? null : valor.trim(); }

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
