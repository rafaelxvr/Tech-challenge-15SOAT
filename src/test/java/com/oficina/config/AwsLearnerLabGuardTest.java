package com.oficina.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsLearnerLabGuardTest {
    private static MockEnvironment ambienteValido() {
        return new MockEnvironment().withProperty("AWS_REGION", "us-east-1")
                .withProperty("oficina.aws.credenciais", "cadeia-padrao");
    }

    @Test void aceitaAmbienteLearnerLabSemEndpointNemCredencialEstatica() {
        assertThatCode(() -> AwsLearnerLabGuard.validar(ambienteValido())).doesNotThrowAnyException();
    }

    @Test void aceitaEndpointRealDaAws() {
        assertThatCode(() -> AwsLearnerLabGuard.validar(
                ambienteValido().withProperty("SAGA_SQS_ENDPOINT", "https://sqs.us-east-1.amazonaws.com")))
                .doesNotThrowAnyException();
    }

    @Test void recusaEndpointDoLocalStack() {
        assertThatThrownBy(() -> AwsLearnerLabGuard.validar(
                ambienteValido().withProperty("AWS_ENDPOINT_URL", "http://localstack.infra:4566")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AWS_ENDPOINT_URL");
    }

    @Test void recusaEndpointQueImitaOHostDaAws() {
        for (String falso : new String[]{"https://sqs.amazonaws.com.evil.example", "https://x@sqs.us-east-1.amazonaws.com",
                "http://sqs.us-east-1.amazonaws.com", "not a uri"}) {
            assertThatThrownBy(() -> AwsLearnerLabGuard.validar(ambienteValido().withProperty("SAGA_SQS_ENDPOINT", falso)))
                    .as(falso).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void recusaCredenciaisEstaticasOuDummyEMantemOValorForaDaMensagem() {
        assertThatThrownBy(() -> AwsLearnerLabGuard.validar(
                ambienteValido().withProperty("AWS_ACCESS_KEY_ID", "test").withProperty("AWS_SECRET_ACCESS_KEY", "s3cr3t-value")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS_ACCESS_KEY_ID").hasMessageContaining("AWS_SECRET_ACCESS_KEY")
                .hasMessageNotContaining("s3cr3t-value");
    }

    @Test void recusaRegiaoForaDoLabOuAusente() {
        assertThatThrownBy(() -> AwsLearnerLabGuard.validar(ambienteValido().withProperty("AWS_REGION", "sa-east-1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AWS_REGION");
        assertThatThrownBy(() -> AwsLearnerLabGuard.validar(new MockEnvironment()
                .withProperty("oficina.aws.credenciais", "cadeia-padrao")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AWS_REGION");
    }

    @Test void recusaModoDeCredencialDiferenteDeCadeiaPadrao() {
        assertThatThrownBy(() -> AwsLearnerLabGuard.validar(new MockEnvironment().withProperty("AWS_REGION", "us-east-1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("oficina.aws.credenciais");
    }

    @Test void guardaSoExisteNoPerfilAws() {
        ApplicationContextRunner contexto = new ApplicationContextRunner().withUserConfiguration(AwsLearnerLabGuard.class)
                .withPropertyValues("AWS_ENDPOINT_URL=http://localstack.infra:4566");
        contexto.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AwsLearnerLabGuard.class));
        contexto.withInitializer(c -> c.getEnvironment().setActiveProfiles("aws"))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
