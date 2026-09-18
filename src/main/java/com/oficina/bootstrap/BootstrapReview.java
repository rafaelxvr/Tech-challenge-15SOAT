package com.oficina.bootstrap;

import java.util.Map;
import java.util.Set;

/** Public references only. The approval digest and source archive are verified by the release adapter. */
public record BootstrapReview(int schemaVersion, String environment, String sourceCommit,
                              String databaseHost, String caSha256, SecretReference master,
                              Map<String, SecretReference> roles) {
    public static final Set<String> ROLE_KEYS = Set.of("migration", "app", "auth", "notification");

    public BootstrapReview {
        if (schemaVersion != 1 || !Set.of("staging", "production").contains(environment)
                || sourceCommit == null || !sourceCommit.matches("[a-f0-9]{40}")
                || databaseHost == null || !databaseHost.matches("[a-z0-9][a-z0-9.-]+")
                || caSha256 == null || !caSha256.matches("[a-f0-9]{64}")
                || master == null || !master.arn().matches("arn:aws:secretsmanager:us-east-1:[0-9]{12}:secret:rds!db-[A-Za-z0-9-]+")
                || roles == null || !roles.keySet().equals(ROLE_KEYS)) {
            throw new IllegalArgumentException("Invalid bootstrap review contract");
        }
        String account = master.arn().split(":")[4];
        for (var entry : roles.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().arn().matches(
                    "arn:aws:secretsmanager:us-east-1:" + account + ":secret:oficina/" + environment
                            + "/" + entry.getKey() + "-[A-Za-z0-9]{6}")) {
                throw new IllegalArgumentException("Role secret must be its exact same-account/environment reference");
            }
        }
        roles = Map.copyOf(roles);
    }

    public String username(String key) {
        if (!ROLE_KEYS.contains(key)) throw new IllegalArgumentException("Unknown role");
        return "oficina_" + environment + "_" + key;
    }

    public record SecretReference(String arn, String versionId) {
        public SecretReference {
            if (arn == null || versionId == null || !versionId.matches("[A-Za-z0-9-]{32,64}")) {
                throw new IllegalArgumentException("An exact immutable secret version is required");
            }
        }
    }
}
