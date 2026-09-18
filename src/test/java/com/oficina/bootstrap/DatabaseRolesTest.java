package com.oficina.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL 16 authorization proof; Docker is required, never silently skipped. */
class DatabaseRolesTest {
    @Test void upgradesV4WithNonSuperuserMasterAndProvesRestrictedIdempotentRoles() throws Exception {
        exercise(true);
    }
    @Test void bootstrapsEmptyDatabaseAndRejectsUnsafeExistingAuthority() throws Exception {
        exercise(false);
    }

    private void exercise(boolean upgrade) throws Exception {
        try (var postgres=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("oficina")) {
            postgres.start();
            var review=review();
            String masterPassword=UUID.randomUUID().toString();
            try (var admin=DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); var sql=admin.createStatement()) {
                sql.execute("CREATE ROLE bootstrap_master LOGIN CREATEROLE CREATEDB PASSWORD '"+masterPassword+"'");
                sql.execute("ALTER DATABASE oficina OWNER TO bootstrap_master");
                sql.execute("ALTER SCHEMA public OWNER TO bootstrap_master");
            }
            if(upgrade) Flyway.configure().dataSource(postgres.getJdbcUrl(), "bootstrap_master", masterPassword).target("4").load().migrate();
            var credentials=new LinkedHashMap<String,BootstrapSecrets.Credential>();
            for(String key:BootstrapReview.ROLE_KEYS) credentials.put(key,new BootstrapSecrets.Credential(review.username(key),UUID.randomUUID().toString()+"'quote"));
            var bootstrap=new DatabaseBootstrap();
            try(var master=DriverManager.getConnection(postgres.getJdbcUrl(),"bootstrap_master",masterPassword)) {
                bootstrap.prepare(master,review,credentials);
                bootstrap.prepare(master,review,credentials);
            }
            var migration=credentials.get("migration");
            try(var connection=connect(postgres,migration)) {
                assertThatThrownBy(()->bootstrap.grantRuntime(connection,review)).hasMessageContaining("V8");
            }
            Flyway.configure().dataSource(postgres.getJdbcUrl(),migration.username(),migration.password()).target("8").load().migrate();
            try(var connection=connect(postgres,migration)) {
                bootstrap.grantRuntime(connection,review);
                bootstrap.grantRuntime(connection,review);
                try(var sql=connection.createStatement()) {
                    sql.execute("CREATE TABLE public.future_restricted(id integer)");
                    sql.execute("CREATE FUNCTION public.future_restricted_function() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
                    try(var rows=sql.executeQuery("SELECT ativo FROM public.usuarios WHERE email='admin@oficina.com'")) { rows.next(); assertThat(rows.getBoolean(1)).isFalse(); }
                }
            }
            for(String key:java.util.List.of("app","auth","notification")) {
                try(var connection=connect(postgres,credentials.get(key))) {
                    BootstrapMain.verifyRuntime(connection,key);
                    denied(connection,"SELECT * FROM public.future_restricted");
                    denied(connection,"SELECT public.future_restricted_function()");
                    denied(connection,"ALTER TABLE public.clientes ADD COLUMN forbidden integer");
                    denied(connection,"SET ROLE " + review.username("migration"));
                    try(var sql=connection.createStatement(); var result=sql.executeQuery("SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls,rolinherit FROM pg_roles WHERE rolname=current_user")) {
                        result.next(); for(int index=1;index<=6;index++) assertThat(result.getBoolean(index)).isFalse();
                    }
                }
            }
            try(var app=connect(postgres,credentials.get("app"));var sql=app.createStatement()) {
                app.setAutoCommit(false);
                sql.execute("INSERT INTO clientes(nome,tipo_documento,documento,email,telefone) VALUES ('Fixture','CPF','39053344705','fixture@example.invalid','000')");
                sql.execute("UPDATE clientes SET nome='Changed' WHERE documento='39053344705'");
                sql.execute("DELETE FROM clientes WHERE documento='39053344705'");
                sql.execute("SELECT nextval('ordens_servico_numero_seq')");
                app.rollback();
            }
            try(var master=DriverManager.getConnection(postgres.getJdbcUrl(),"bootstrap_master",masterPassword)) {
                assertThatThrownBy(()->bootstrap.prepare(master,review,credentials)).hasMessageContaining("Unreviewed APP schema");
                try(var sql=master.createStatement()) {
                    sql.execute("DROP TABLE public.future_restricted");
                    sql.execute("DROP FUNCTION public.future_restricted_function()");
                }
            }
            // A rerun may neither normalize nor silently accept privileged pre-existing role drift.
            try(var admin=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var sql=admin.createStatement()) {
                sql.execute("ALTER ROLE " + review.username("auth") + " CREATEDB");
            }
            try(var master=DriverManager.getConnection(postgres.getJdbcUrl(),"bootstrap_master",masterPassword)) {
                assertThatThrownBy(()->bootstrap.prepare(master,review,credentials)).hasMessageContaining("unsafe authority");
            }
        }
    }
    private Connection connect(PostgreSQLContainer<?> postgres,BootstrapSecrets.Credential credential) throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(),credential.username(),credential.password());
    }
    private void denied(Connection connection,String query) {
        assertThatThrownBy(()->{try(var statement=connection.createStatement()){statement.execute(query);}})
                .isInstanceOf(java.sql.SQLException.class).extracting("SQLState").isEqualTo("42501");
    }
    static BootstrapReview review() {
        var roles=new LinkedHashMap<String,BootstrapReview.SecretReference>();
        for(String key:BootstrapReview.ROLE_KEYS) roles.put(key,new BootstrapReview.SecretReference("arn:aws:secretsmanager:us-east-1:123456789012:secret:oficina/staging/"+key+"-AbCdEf","a".repeat(32)));
        return new BootstrapReview(1,"staging","b".repeat(40),"database.example.invalid","c".repeat(64),
                new BootstrapReview.SecretReference("arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-reviewed-AbCdEf","d".repeat(32)),roles);
    }
}
