package com.oficina.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsLearnerLabGuardTest {
    @TempDir Path home;

    private void validar(MockEnvironment env) { AwsLearnerLabGuard.validar(env, home); }

    private void recusa(String nome, MockEnvironment env) {
        assertThatThrownBy(() -> validar(env.withProperty("AWS_REGION", "us-east-1")))
                .as(nome).isInstanceOf(IllegalStateException.class).hasMessageContaining(nome);
    }
    private static MockEnvironment ambienteValido() {
        return new MockEnvironment().withProperty("AWS_REGION", "us-east-1")
                .withProperty("oficina.aws.credenciais", "cadeia-padrao");
    }

    @Test void aceitaAmbienteLearnerLabSemEndpointNemCredencialEstatica() {
        assertThatCode(() -> validar(ambienteValido())).doesNotThrowAnyException();
    }

    @Test void aceitaEndpointRealDaAws() {
        assertThatCode(() -> validar(
                ambienteValido().withProperty("SAGA_SQS_ENDPOINT", "https://sqs.us-east-1.amazonaws.com")))
                .doesNotThrowAnyException();
    }

    @Test void recusaEndpointDoLocalStack() {
        assertThatThrownBy(() -> validar(
                ambienteValido().withProperty("AWS_ENDPOINT_URL", "http://localstack.infra:4566")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AWS_ENDPOINT_URL");
    }

    @Test void recusaEndpointQueImitaOHostDaAws() {
        for (String falso : new String[]{"https://sqs.amazonaws.com.evil.example", "https://x@sqs.us-east-1.amazonaws.com",
                "http://sqs.us-east-1.amazonaws.com", "not a uri"}) {
            assertThatThrownBy(() -> validar(ambienteValido().withProperty("SAGA_SQS_ENDPOINT", falso)))
                    .as(falso).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void recusaCredenciaisEstaticasOuDummyEMantemOValorForaDaMensagem() {
        assertThatThrownBy(() -> validar(
                ambienteValido().withProperty("AWS_ACCESS_KEY_ID", "test").withProperty("AWS_SECRET_ACCESS_KEY", "s3cr3t-value")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS_ACCESS_KEY_ID").hasMessageContaining("AWS_SECRET_ACCESS_KEY")
                .hasMessageNotContaining("s3cr3t-value");
    }

    @Test void recusaRegiaoForaDoLabOuAusente() {
        assertThatThrownBy(() -> validar(ambienteValido().withProperty("AWS_REGION", "sa-east-1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AWS_REGION");
        assertThatThrownBy(() -> validar(new MockEnvironment()
                .withProperty("oficina.aws.credenciais", "cadeia-padrao")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AWS_REGION");
    }

    @Test void recusaModoDeCredencialDiferenteDeCadeiaPadrao() {
        assertThatThrownBy(() -> validar(new MockEnvironment().withProperty("AWS_REGION", "us-east-1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oficina.aws.credenciais");
    }

    @Test void recusaFontesDeCredencialAnterioresAoImds() {
        for (String nome : new String[]{"aws.accessKeyId", "aws.secretAccessKey", "aws.sessionToken", "AWS_PROFILE", "aws.profile",
                "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "AWS_CONTAINER_CREDENTIALS_FULL_URI", "AWS_CONTAINER_AUTHORIZATION_TOKEN",
                "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", "AWS_WEB_IDENTITY_TOKEN_FILE", "aws.webIdentityTokenFile", "AWS_ROLE_ARN",
                "aws.roleArn", "AWS_SESSION_TOKEN"}) {
            recusa(nome, ambienteValido().withProperty(nome, "x"));
        }
    }

    @Test void recusaOverridesDeEndpointPorServicoEPorJvm() {
        recusa("AWS_ENDPOINT_URL_S3", ambienteValido().withProperty("AWS_ENDPOINT_URL_S3", "http://localstack.infra:4566"));
        recusa("AWS_ENDPOINT_URL_SECRETS_MANAGER", ambienteValido().withProperty("AWS_ENDPOINT_URL_SECRETS_MANAGER", "https://evil.example"));
        recusa("aws.endpointUrl", ambienteValido().withProperty("aws.endpointUrl", "http://localhost:4566"));
        recusa("aws.endpointUrlSqs", ambienteValido().withProperty("aws.endpointUrlSqs", "https://sqs.amazonaws.com.evil.example"));
        recusa("AWS_EC2_METADATA_SERVICE_ENDPOINT", ambienteValido().withProperty("AWS_EC2_METADATA_SERVICE_ENDPOINT", "http://127.0.0.1:1338"));
        recusa("aws.ec2MetadataServiceEndpoint", ambienteValido().withProperty("aws.ec2MetadataServiceEndpoint", "http://127.0.0.1:1338"));
    }

    @Test void aceitaEndpointPorServicoApontandoParaAmazonAws() {
        assertThatCode(() -> validar(ambienteValido().withProperty("AWS_ENDPOINT_URL_SQS", "https://sqs.us-east-1.amazonaws.com")))
                .doesNotThrowAnyException();
    }

    @Test void recusaImdsDesligadoERegioesAlternativasForaDoLab() {
        recusa("AWS_EC2_METADATA_DISABLED", ambienteValido().withProperty("AWS_EC2_METADATA_DISABLED", "true"));
        recusa("aws.region", ambienteValido().withProperty("aws.region", "sa-east-1"));
        recusa("AWS_DEFAULT_REGION", ambienteValido().withProperty("AWS_DEFAULT_REGION", "eu-west-1"));
    }

    @Test void enumeraEndpointsEmPropertySourceDoSistemaMesmoSemNomeConhecido() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("teste", Map.of("AWS_REGION", "us-east-1",
                "oficina.aws.credenciais", "cadeia-padrao", "AWS_ENDPOINT_URL_BEDROCK_RUNTIME", "http://localhost:9")));
        assertThatThrownBy(() -> AwsLearnerLabGuard.validar(env, home)).hasMessageContaining("AWS_ENDPOINT_URL_BEDROCK_RUNTIME");
    }

    @Test void recusaArquivoCompartilhadoComCredencialOuEndpointSemVazarValor() throws IOException {
        Path aws = Files.createDirectories(home.resolve(".aws"));
        Files.writeString(aws.resolve("credentials"), "[default]\naws_access_key_id = AKIAEXEMPLO\naws_secret_access_key = s3cr3t-value\n");
        assertThatThrownBy(() -> validar(ambienteValido())).hasMessageContaining("aws_access_key_id")
                .hasMessageContaining("aws_secret_access_key").hasMessageNotContaining("s3cr3t-value").hasMessageNotContaining("AKIAEXEMPLO");
        Files.delete(aws.resolve("credentials"));
        Files.writeString(aws.resolve("config"), "[default]\nregion = us-east-1\nendpoint_url = http://localstack.infra:4566\n");
        assertThatThrownBy(() -> validar(ambienteValido())).hasMessageContaining("endpoint_url");
        Files.writeString(aws.resolve("config"), "[default]\nregion = us-east-1\nendpoint_url = https://sqs.us-east-1.amazonaws.com\n");
        assertThatCode(() -> validar(ambienteValido())).doesNotThrowAnyException();
    }

    @Test void respeitaCaminhoDeArquivoCompartilhadoInformadoPorVariavel() throws IOException {
        Path outro = home.resolve("outro-config");
        Files.writeString(outro, "[profile x]\ncredential_process = /bin/true\n");
        recusa("credential_process", ambienteValido().withProperty("AWS_CONFIG_FILE", outro.toString()));
    }

    @Test void guardaSoExisteNoPerfilAws() {
        ApplicationContextRunner contexto = new ApplicationContextRunner().withUserConfiguration(AwsLearnerLabGuard.class)
                .withPropertyValues("AWS_ENDPOINT_URL=http://localstack.infra:4566");
        contexto.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AwsLearnerLabGuard.class));
        contexto.withInitializer(c -> c.getEnvironment().setActiveProfiles("aws"))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
