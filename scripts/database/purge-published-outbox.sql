-- Run with psql -X -v ON_ERROR_STOP=1 -f scripts/database/purge-published-outbox.sql
-- Default: preview only. After inspecting it, explicitly pass -v delete_published=true.
-- Maintenance exports the exact locked candidates to ./outbox-published-export.csv
-- BEFORE deletion. Preserve/rename an existing export before a subsequent run.
\set ON_ERROR_STOP on
\if :{?delete_published}
\else
  \set delete_published false
\endif
BEGIN;
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '30s';
-- BEGIN CANDIDATES
CREATE TEMP TABLE outbox_purge_candidates ON COMMIT DROP AS
SELECT e.* FROM outbox_eventos e
WHERE e.estado = 'PUBLISHED'
  AND e.publicado_em < CURRENT_TIMESTAMP - INTERVAL '7 days'
  AND NOT EXISTS (SELECT 1 FROM outbox_recuperacoes r WHERE r.event_id = e.event_id)
FOR UPDATE OF e;
-- END CANDIDATES
SELECT event_id, os_id, sequencia, estado, publicado_em FROM outbox_purge_candidates
ORDER BY publicado_em, event_id;
\if :delete_published
  \copy outbox_purge_candidates TO 'outbox-published-export.csv' WITH (FORMAT CSV, HEADER true)
-- BEGIN DELETE
DELETE FROM outbox_eventos e USING outbox_purge_candidates c
WHERE e.event_id = c.event_id AND e.estado = 'PUBLISHED'
  AND e.publicado_em < CURRENT_TIMESTAMP - INTERVAL '7 days'
  AND NOT EXISTS (SELECT 1 FROM outbox_recuperacoes r WHERE r.event_id = e.event_id);
-- END DELETE
  COMMIT;
\else
  ROLLBACK;
\endif
-- PENDING, BLOCKED, SKIPPED and every event with recovery audit are retained.
-- Inspected BLOCKED retry/skip belongs to R4's recover-outbox.ps1 command:
-- lock exact event, check predecessor dependencies, require operator/reason/action,
-- append outbox_recuperacoes in the SAME transaction. Never bulk-skip or auto-recover.
