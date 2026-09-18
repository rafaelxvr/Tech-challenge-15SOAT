package com.oficina.bootstrap;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Administrative release operation, not an application service or Spring bean. */
public final class DatabaseBootstrap {
    static final List<String> TABLES = List.of("usuarios", "clientes", "veiculos", "servicos", "pecas",
            "ordens_servico", "os_servicos", "os_pecas", "os_historico", "cliente_identidade_auditoria",
            "outbox_eventos", "outbox_recuperacoes");
    static final List<String> VIEWS = List.of("auth_cliente_snapshot", "notificacao_destinatario_snapshot");
    static final List<String> TYPES = List.of("tipo_documento", "status_os", "role_usuario");

    public void prepare(Connection master, BootstrapReview review,
                        Map<String, BootstrapSecrets.Credential> credentials) throws SQLException {
        if (!credentials.keySet().equals(BootstrapReview.ROLE_KEYS)) throw new IllegalArgumentException("Missing role credentials");
        for (String key : BootstrapReview.ROLE_KEYS) {
            var credential = credentials.get(key);
            if (!review.username(key).equals(credential.username()) || credential.password().length() < 32) {
                throw new IllegalArgumentException("Credential identity/strength does not match the reviewed role");
            }
        }
        transaction(master, () -> {
            execute(master, "SELECT pg_advisory_xact_lock(153003)");
            assertReviewedSchema(master);
            execute(master, "SET LOCAL password_encryption = 'scram-sha-256'");
            for (String key : BootstrapReview.ROLE_KEYS) {
                String name = review.username(key);
                if (!exists(master, "SELECT 1 FROM pg_roles WHERE rolname = ?", name)) {
                    execute(master, "CREATE ROLE " + quote(name) + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT");
                }
                assertRestricted(master, name);
                // Bound input stays out of generated SQL. No provider/SQL exception is printed by the launcher.
                try (var password = master.prepareStatement("SELECT set_config('oficina.bootstrap_password', ?, true)")) {
                    password.setString(1, credentials.get(key).password()); password.execute();
                }
                execute(master, "DO $$ BEGIN EXECUTE format('ALTER ROLE %I LOGIN PASSWORD %L', '" + name
                        + "', current_setting('oficina.bootstrap_password')); END $$");
                execute(master, "SELECT set_config('oficina.bootstrap_password', '', true)");
                execute(master, "ALTER ROLE " + quote(name) + " SET search_path TO public, pg_catalog");
                execute(master, "REVOKE ALL ON DATABASE oficina FROM " + quote(name));
                execute(master, "GRANT CONNECT ON DATABASE oficina TO " + quote(name));
            }
            String migration = quote(review.username("migration"));
            // PostgreSQL 16 CREATEROLE grants creator ADMIN; allow schema ownership transfer without runtime membership.
            execute(master, "GRANT " + migration + " TO CURRENT_USER WITH SET TRUE, INHERIT TRUE");
            execute(master, "REVOKE ALL ON DATABASE oficina FROM PUBLIC");
            execute(master, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
            execute(master, "CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            execute(master, "ALTER SCHEMA public OWNER TO " + migration);
            for (String table : TABLES) transferRelation(master, table, "TABLE", migration);
            transferRelation(master, "flyway_schema_history", "TABLE", migration);
            for (String view : VIEWS) transferRelation(master, view, "VIEW", migration);
            transferRelation(master, "ordens_servico_numero_seq", "SEQUENCE", migration);
            for (String type : TYPES) {
                if (exists(master, "SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='public' AND t.typname=?", type)) {
                    execute(master, "ALTER TYPE public." + quote(type) + " OWNER TO " + migration);
                }
            }
            if (exists(master, "SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname=?", "update_updated_at_column")) {
                execute(master, "ALTER FUNCTION public.update_updated_at_column() OWNER TO " + migration);
            }
            execute(master, "SET LOCAL ROLE " + migration);
            revokeDefaults(master, review);
        });
    }

    /** Invoke after V8 migration, as the migration role. Does not expose the master to runtime. */
    public void grantRuntime(Connection migration, BootstrapReview review) throws SQLException {
        transaction(migration, () -> {
            execute(migration, "SELECT pg_advisory_xact_lock(153003)");
            try (var statement=migration.createStatement(); var rows=statement.executeQuery("SELECT current_user")) {
                rows.next(); if (!review.username("migration").equals(rows.getString(1))) throw new SQLException("Wrong grant identity");
            }
            if (!exists(migration, "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname=?", "flyway_schema_history")
                    || !exists(migration, "SELECT 1 FROM public.flyway_schema_history WHERE version=? AND success", "8")) {
                throw new SQLException("V8 migration has not completed");
            }
            revokeDefaults(migration, review);
            for (String role : List.of("app", "auth", "notification")) {
                String name=quote(review.username(role));
                execute(migration, "REVOKE ALL ON SCHEMA public FROM " + name);
                execute(migration, "GRANT USAGE ON SCHEMA public TO " + name);
                execute(migration, "REVOKE ALL ON ALL TABLES IN SCHEMA public FROM " + name);
                execute(migration, "REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM " + name);
            }
            for (String table : TABLES) {
                execute(migration, "REVOKE ALL ON TABLE public." + quote(table) + " FROM PUBLIC");
                execute(migration, "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public." + quote(table) + " TO " + quote(review.username("app")));
            }
            execute(migration, "REVOKE ALL ON TABLE public.flyway_schema_history FROM PUBLIC");
            for (String view : VIEWS) execute(migration, "REVOKE ALL ON TABLE public." + quote(view) + " FROM PUBLIC");
            execute(migration, "REVOKE ALL ON SEQUENCE public.ordens_servico_numero_seq FROM PUBLIC");
            execute(migration, "GRANT USAGE, SELECT ON SEQUENCE public.ordens_servico_numero_seq TO " + quote(review.username("app")));
            for (String type : TYPES) {
                execute(migration, "REVOKE ALL ON TYPE public." + quote(type) + " FROM PUBLIC");
                execute(migration, "GRANT USAGE ON TYPE public." + quote(type) + " TO " + quote(review.username("app")));
            }
            execute(migration, "REVOKE ALL ON FUNCTION public.update_updated_at_column() FROM PUBLIC");
            execute(migration, "GRANT SELECT ON public.auth_cliente_snapshot TO " + quote(review.username("auth")));
            execute(migration, "GRANT SELECT ON public.notificacao_destinatario_snapshot TO " + quote(review.username("notification")));
            // Historical seed remains immutable; never activate its known development password in the cloud.
            execute(migration, "UPDATE public.usuarios SET ativo=false WHERE email='admin@oficina.com' AND ativo=true");
        });
    }

    private void assertRestricted(Connection connection, String name) throws SQLException {
        if (exists(connection, "SELECT 1 FROM pg_roles WHERE rolname=? AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit)", name)
                || exists(connection, "SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname=?", name)
                || exists(connection, "SELECT 1 FROM pg_database d JOIN pg_roles r ON r.oid=d.datdba WHERE r.rolname=?", name)) {
            throw new SQLException("Existing bootstrap role has unsafe authority");
        }
        if (!name.endsWith("_migration") &&
                (exists(connection, "SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid=n.nspowner WHERE r.rolname=?", name)
                || exists(connection, "SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid=c.relowner WHERE r.rolname=?", name)
                || exists(connection, "SELECT 1 FROM pg_proc p JOIN pg_roles r ON r.oid=p.proowner WHERE r.rolname=?", name))) {
            throw new SQLException("Runtime role must not own schema objects");
        }
    }
    private void assertReviewedSchema(Connection connection) throws SQLException {
        var names=new java.util.ArrayList<>(TABLES);
        names.addAll(VIEWS); names.add("flyway_schema_history"); names.add("ordens_servico_numero_seq");
        String allowed=names.stream().map(name -> "'"+name+"'").collect(java.util.stream.Collectors.joining(","));
        try(var statement=connection.createStatement();var rows=statement.executeQuery(
                "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' "
                + "AND c.relkind IN ('r','p','v','m','S','f') AND c.relname NOT IN ("+allowed+") "
                + "AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid='pg_class'::regclass AND d.objid=c.oid AND d.deptype='e')")) {
            if(rows.next()) throw new SQLException("Unreviewed APP schema objects");
        }
        try(var statement=connection.createStatement();var rows=statement.executeQuery(
                "SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' "
                + "AND (p.proname <> 'update_updated_at_column' OR p.pronargs <> 0) "
                + "AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid='pg_proc'::regclass AND d.objid=p.oid AND d.deptype='e')")) {
            if(rows.next()) throw new SQLException("Unreviewed APP schema functions");
        }
    }
    private void transferRelation(Connection connection, String name, String kind, String owner) throws SQLException {
        if (exists(connection, "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname=?", name)) {
            execute(connection, "ALTER " + kind + " public." + quote(name) + " OWNER TO " + owner);
        }
    }
    private void revokeDefaults(Connection connection, BootstrapReview review) throws SQLException {
        execute(connection, "ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM PUBLIC");
        execute(connection, "ALTER DEFAULT PRIVILEGES REVOKE ALL ON SEQUENCES FROM PUBLIC");
        execute(connection, "ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
        execute(connection, "ALTER DEFAULT PRIVILEGES REVOKE USAGE ON TYPES FROM PUBLIC");
        execute(connection, "ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC");
        execute(connection, "ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM PUBLIC");
        execute(connection, "ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC");
        execute(connection, "ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE USAGE ON TYPES FROM PUBLIC");
        for (String key : List.of("app", "auth", "notification")) {
            for (String scope : List.of("", " IN SCHEMA public")) {
                for (String kind : List.of("TABLES", "SEQUENCES", "FUNCTIONS", "TYPES")) {
                    execute(connection, "ALTER DEFAULT PRIVILEGES" + scope + " REVOKE ALL ON " + kind + " FROM " + quote(review.username(key)));
                }
            }
        }
    }
    private static String quote(String name) { return '"' + name.replace("\"", "\"\"") + '"'; }
    private static boolean exists(Connection connection, String sql, String argument) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument); try (var result=statement.executeQuery()) { return result.next(); }
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement=connection.createStatement()) { statement.execute(sql); }
    }
    private interface SqlAction { void run() throws SQLException; }
    private void transaction(Connection connection, SqlAction action) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Bootstrap requires a dedicated connection");
        connection.setAutoCommit(false);
        try { action.run(); connection.commit(); }
        catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        finally { connection.setAutoCommit(true); }
    }
}
