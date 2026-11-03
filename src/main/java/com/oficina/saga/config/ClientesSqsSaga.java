package com.oficina.saga.config;

import com.oficina.config.OutboxConfiguration;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsWebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.utils.IoUtils;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** Owns every AWS client the saga uses: IRSA in the cluster, static test credentials against LocalStack. */
public final class ClientesSqsSaga implements AutoCloseable {
    private final SdkHttpClient http;
    private final StsClient sts;
    private final AwsCredentialsProvider credenciais;
    private final SqsClient envio;
    private final SqsClient recepcao;

    private ClientesSqsSaga(SdkHttpClient http, StsClient sts, AwsCredentialsProvider credenciais,
                            SqsClient envio, SqsClient recepcao) {
        this.http = http; this.sts = sts; this.credenciais = credenciais; this.envio = envio; this.recepcao = recepcao;
    }

    public static ClientesSqsSaga criar(SagaProperties.Mensageria m, String roleArn, String tokenFile) {
        SdkHttpClient http = UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                .socketTimeout(Duration.ofSeconds(15)).build();
        Region region = Region.of(m.region());
        boolean local = m.endpoint() != null && !m.endpoint().isBlank();
        StsClient sts = null;
        AwsCredentialsProvider credenciais;
        if (local) {
            credenciais = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        } else {
            // AssumeRoleWithWebIdentity is unsigned. Never recurse into the default credentials chain.
            sts = StsClient.builder().region(region).credentialsProvider(AnonymousCredentialsProvider.create())
                    .overrideConfiguration(OutboxConfiguration.sdkLimits()).httpClient(http).build();
            credenciais = StsWebIdentityTokenFileCredentialsProvider.builder().stsClient(sts).roleArn(roleArn)
                    .roleSessionName("oficina-saga").webIdentityTokenFile(Path.of(tokenFile))
                    .asyncCredentialUpdateEnabled(false).build();
        }
        SqsClient envio = sqs(region, credenciais, http, m, OutboxConfiguration.sdkLimits());
        SqsClient recepcao = sqs(region, credenciais, http, m, ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(15))
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()).build());
        return new ClientesSqsSaga(http, sts, credenciais, envio, recepcao);
    }

    private static SqsClient sqs(Region region, AwsCredentialsProvider credenciais, SdkHttpClient http,
                                 SagaProperties.Mensageria m, ClientOverrideConfiguration limites) {
        SqsClientBuilder builder = SqsClient.builder().region(region).credentialsProvider(credenciais)
                .httpClient(http).overrideConfiguration(limites);
        if (m.endpoint() != null && !m.endpoint().isBlank()) builder.endpointOverride(URI.create(m.endpoint()));
        return builder.build();
    }

    public SqsClient envio() { return envio; }
    public SqsClient recepcao() { return recepcao; }

    @Override public void close() {
        IoUtils.closeQuietly(recepcao, null);
        IoUtils.closeQuietly(envio, null);
        if (credenciais instanceof AutoCloseable fechavel) IoUtils.closeQuietly(fechavel, null);
        IoUtils.closeQuietly(sts, null);
        IoUtils.closeQuietly(http, null);
    }
}
