-- Idempotent runtime grants for the billing database (same contract as the billing repo's k8s/local-deps/postgres-init/01-roles.sh).
-- Run as the instance superuser by scripts/phase4-kind-init.ps1, BEFORE the billing Flyway: billing-service migrates itself at
-- startup as :migrator, so the grants are default privileges that cover every table/sequence the migrator creates afterwards.
-- :migrator / :app are psql variables. The runtime role gets DML only (SELECT/INSERT/UPDATE); the conditional DELETE of
-- V2__webhook_replay.sql is granted by the migration itself.
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE ON TABLES TO %I', :'migrator', :'app') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO %I', :'migrator', :'app') \gexec
-- Re-runs against a database already migrated: default privileges do not retro-apply.
SELECT format('GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO %I', :'app') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'app') \gexec
