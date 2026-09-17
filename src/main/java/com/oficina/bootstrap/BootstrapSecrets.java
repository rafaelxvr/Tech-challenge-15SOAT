package com.oficina.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/** Read-only secret adapter. Never logs provider responses, credentials or exception causes. */
public final class BootstrapSecrets {
    private final SecretsManagerClient client;
    private final ObjectMapper json = new ObjectMapper();

    public BootstrapSecrets(SecretsManagerClient client) { this.client = client; }

    public Credential read(BootstrapReview.SecretReference reference) {
        try {
            var response = client.getSecretValue(GetSecretValueRequest.builder()
                    .secretId(reference.arn()).versionId(reference.versionId()).build());
            if (!reference.arn().equals(response.arn()) || !reference.versionId().equals(response.versionId())) {
                throw new IllegalArgumentException();
            }
            var document = json.readTree(response.secretString());
            return new Credential(document.path("username").asText(), document.path("password").asText());
        } catch (Exception ignored) {
            throw new IllegalStateException("BOOTSTRAP_SECRET_READ_FAILED");
        }
    }

    public static final class Credential {
        private final String username;
        private final String password;
        public Credential(String username, String password) {
            if (username == null || !username.matches("[a-z][a-z0-9_]{0,62}")
                    || password == null || password.length() < 16 || password.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid credential structure");
            }
            this.username = username;
            this.password = password;
        }
        public String username() { return username; }
        String password() { return password; }
        @Override public String toString() { return "Credential[REDACTED]"; }
    }
}
