package com.oficina.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Separate release entrypoint. No Spring context, automatic startup hook, secret creation or publication. */
public final class BootstrapMain {
    private BootstrapMain() { }

    public static void main(String[] args) {
        try {
            if (args.length != 4) throw new IllegalArgumentException();
            Path input=Path.of(args[0]);
            if (!args[1].matches("[a-f0-9]{64}") || !sha256(input).equals(args[1])) throw new IllegalArgumentException();
            var json=new ObjectMapper();
            var review=json.readValue(Files.readAllBytes(input), BootstrapReview.class);
            Path ca=Path.of(args[2]).toAbsolutePath();
            if (!sha256(ca).equals(review.caSha256())) throw new IllegalArgumentException();
            Path output=Path.of(args[3]);
            // Refuse stale successful receipts and never overwrite a previous attempt.
            if (Files.exists(output)) throw new IllegalArgumentException();
            try (var client=SecretsManagerClient.builder().region(Region.US_EAST_1)
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(10)))
                    .build()) {
                var secrets=new BootstrapSecrets(client);
                var roles=new LinkedHashMap<String, BootstrapSecrets.Credential>();
                for (String key : BootstrapReview.ROLE_KEYS) roles.put(key, secrets.read(review.roles().get(key)));
                var master=secrets.read(review.master());
                var bootstrap=new DatabaseBootstrap();
                try (var connection=connect(review, ca, master)) { bootstrap.prepare(connection, review, roles); }
                var migration=roles.get("migration");
                String url=jdbcUrl(review, ca);
                Flyway.configure().dataSource(url, migration.username(), migration.password())
                        .locations("classpath:db/migration").target("8").cleanDisabled(true)
                        .baselineOnMigrate(false).outOfOrder(false).validateOnMigrate(true).load().migrate();
                try (var connection=connect(review, ca, migration)) { bootstrap.grantRuntime(connection, review); }
                for (String key : java.util.List.of("app", "auth", "notification")) {
                    try (var connection=connect(review, ca, roles.get(key))) { verifyRuntime(connection, key); }
                }
                Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt(review)),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            }
            System.out.println("BOOTSTRAP_VERIFIED: reference-only receipt written");
        } catch (Exception ignored) {
            // SQL/SDK exceptions can contain query parameters, server details or secret material.
            System.err.println("BOOTSTRAP_FAILED: no successful receipt; inspect restricted evidence");
            System.exit(1);
        }
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    static String jdbcUrl(BootstrapReview review, Path ca) {
        return "jdbc:postgresql://" + review.databaseHost() + ":5432/oficina?sslmode=verify-full&sslrootcert="
                + java.net.URLEncoder.encode(ca.toString(), java.nio.charset.StandardCharsets.UTF_8)
                + "&connectTimeout=10&socketTimeout=60";
    }
    private static Connection connect(BootstrapReview review, Path ca, BootstrapSecrets.Credential credential) throws SQLException {
        var properties=new Properties();
        properties.setProperty("user", credential.username());
        properties.setProperty("password", credential.password());
        return DriverManager.getConnection(jdbcUrl(review, ca), properties);
    }
    static Map<String, Object> receipt(BootstrapReview review) {
        var outputs=new LinkedHashMap<String,Object>();
        outputs.put("schemaVersion", "V8"); outputs.put("authViewVersion", "V5"); outputs.put("recipientViewVersion", "V7");
        for (var entry : Map.of("migration", "migrationSecret", "app", "appSecret", "auth", "authLookupSecret", "notification", "notificationLookupSecret").entrySet()) {
            var reference=review.roles().get(entry.getKey());
            outputs.put(entry.getValue()+"Arn", reference.arn());
            outputs.put(entry.getValue()+"VersionId", reference.versionId());
        }
        return Map.of("schemaVersion", 2, "environment", review.environment(), "sourceCommit", review.sourceCommit(), "outputs", outputs);
    }
    static void verifyRuntime(Connection connection, String key) throws SQLException {
        try (var query=connection.createStatement()) {
            String visible = switch (key) {
                case "app" -> "clientes";
                case "auth" -> "auth_cliente_snapshot";
                case "notification" -> "notificacao_destinatario_snapshot";
                default -> throw new IllegalArgumentException("Unknown runtime role");
            };
            query.executeQuery("SELECT * FROM public." + visible + " LIMIT 0").close();
        }
        denied(connection, "CREATE TABLE public.bootstrap_forbidden (id integer)");
        denied(connection, "CREATE TEMP TABLE bootstrap_forbidden (id integer)");
        denied(connection, "SELECT * FROM public.flyway_schema_history LIMIT 0");
        if (!key.equals("app")) {
            for (String table : DatabaseBootstrap.TABLES) {
                denied(connection, "SELECT * FROM public." + table + " LIMIT 0");
                denied(connection, "DELETE FROM public." + table + " WHERE false");
            }
            denied(connection, "SELECT * FROM public." + (key.equals("auth") ? "notificacao_destinatario_snapshot" : "auth_cliente_snapshot") + " LIMIT 0");
        }
    }
    private static void denied(Connection connection, String sql) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement=connection.createStatement()) {
            try { statement.execute(sql); }
            catch (SQLException expected) { if ("42501".equals(expected.getSQLState())) return; throw expected; }
            throw new SQLException("Runtime privilege denial proof failed");
        } finally { connection.rollback(); connection.setAutoCommit(true); }
    }
}
