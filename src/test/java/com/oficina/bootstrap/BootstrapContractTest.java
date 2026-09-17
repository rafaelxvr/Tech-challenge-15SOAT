package com.oficina.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BootstrapContractTest {
    @Test void readsOnlyExactVersionedReferenceAndRedactsCredentials() {
        var client=mock(SecretsManagerClient.class);
        var reference=DatabaseRolesTest.review().roles().get("app");
        String password=UUID.randomUUID().toString();
        when(client.getSecretValue(any(GetSecretValueRequest.class))).thenReturn(GetSecretValueResponse.builder()
                .arn(reference.arn()).versionId(reference.versionId()).secretString("{\"username\":\"oficina_staging_app\",\"password\":\""+password+"\"}").build());
        var credential=new BootstrapSecrets(client).read(reference);
        assertThat(credential.toString()).doesNotContain(password,"oficina_staging_app");
        var request=ArgumentCaptor.forClass(GetSecretValueRequest.class);
        verify(client).getSecretValue(request.capture()); verifyNoMoreInteractions(client);
        assertThat(request.getValue().secretId()).isEqualTo(reference.arn());
        assertThat(request.getValue().versionId()).isEqualTo(reference.versionId());
        assertThat(request.getValue().versionStage()).isNull();
        when(client.getSecretValue(any(GetSecretValueRequest.class))).thenReturn(GetSecretValueResponse.builder()
                .arn(reference.arn()).versionId("wrong-version").secretString(password).build());
        assertThatThrownBy(()->new BootstrapSecrets(client).read(reference)).hasMessage("BOOTSTRAP_SECRET_READ_FAILED").hasNoCause();
        when(client.getSecretValue(any(GetSecretValueRequest.class))).thenThrow(new RuntimeException(password));
        assertThatThrownBy(()->new BootstrapSecrets(client).read(reference)).hasMessage("BOOTSTRAP_SECRET_READ_FAILED").hasNoCause();
    }
    @Test void rejectsUnversionedCrossEnvironmentAndMasterReusedReferences() {
        var good=DatabaseRolesTest.review();
        assertThatThrownBy(()->new BootstrapReview.SecretReference(good.master().arn(),"AWSCURRENT")).isInstanceOf(IllegalArgumentException.class);
        var roles=new LinkedHashMap<>(good.roles()); roles.put("app",good.master());
        assertThatThrownBy(()->new BootstrapReview(1,"staging",good.sourceCommit(),good.databaseHost(),good.caSha256(),good.master(),roles)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new BootstrapReview(1,"production",good.sourceCommit(),good.databaseHost(),good.caSha256(),good.master(),good.roles())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void receiptContainsOnlySchemaAndVersionedRuntimeReferences() throws Exception {
        var review=DatabaseRolesTest.review();
        String receipt=new ObjectMapper().writeValueAsString(BootstrapMain.receipt(review));
        assertThat(receipt).doesNotContain(review.master().arn(),"password","username",review.databaseHost()).contains("V8","V5","V7");
        for(var reference:review.roles().values()) assertThat(receipt).contains(reference.arn(),reference.versionId());
        assertThat(BootstrapMain.jdbcUrl(review,Path.of("/reviewed/ca.pem"))).contains("sslmode=verify-full","sslrootcert=","/oficina?").doesNotContain("password");
    }
}
