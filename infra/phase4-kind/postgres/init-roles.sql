-- Idempotent role bootstrap, run as the instance superuser by scripts/phase4-kind-init.ps1.
-- psql variables: :db, :migrator, :app, :migrator_pw, :app_pw (random per cluster, never versioned).
-- The migrator owns the schema and runs Flyway; the runtime role only gets DML via grants.sql.
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT', :'migrator')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migrator') \gexec
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT', :'app')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'migrator', :'migrator_pw') \gexec
SELECT format('ALTER ROLE %I PASSWORD %L', :'app', :'app_pw') \gexec

SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', :'db', :'migrator', :'app') \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SELECT format('ALTER SCHEMA public OWNER TO %I', :'migrator') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'app') \gexec

-- V1 runs CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; the migration role is not a superuser, so the
-- superuser pre-creates it here (the IF NOT EXISTS in V1 then becomes a no-op).
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
