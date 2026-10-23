-- Idempotent runtime grants for the os database. Run AFTER every migration, as the migration role
-- (D15: tables created after the V8 bootstrap need an explicit grant step).
-- :app is the runtime role. Every table gets SELECT/INSERT/UPDATE only (D15-R): a source audit found no runtime DELETE
-- (the catalog endpoints deactivate rows and no OrdemServico collection is ever pruned). Add DELETE per table with evidence.
SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', :'app') \gexec
SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', :'app') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE ON TABLE public.%I TO %I', tablename, :'app')
  FROM pg_tables
 WHERE schemaname = 'public'
   AND tablename <> 'flyway_schema_history' \gexec
SELECT format('GRANT USAGE, SELECT ON SEQUENCE public.ordens_servico_numero_seq TO %I', :'app')
 WHERE to_regclass('public.ordens_servico_numero_seq') IS NOT NULL \gexec
SELECT format('GRANT USAGE ON TYPE public.%I TO %I', t, :'app')
  FROM unnest(ARRAY['tipo_documento', 'status_os', 'role_usuario']) AS t \gexec
