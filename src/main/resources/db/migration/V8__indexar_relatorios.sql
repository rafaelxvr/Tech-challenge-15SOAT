-- PostgreSQL 16 EXPLAIN (ANALYZE, BUFFERS), 20,000 orders / 120,000 transitions,
-- 20 deliveries in the requested day: full report 34.720 ms -> 17.388 ms,
-- shared buffers 4464 hits -> 1161 hits + 102 reads. Both date predicates
-- use Index Only Scan. The partial delivery index also narrows unknown-date checks.
-- Existing history uniqueness, foreign keys and order/sequence indexes are preserved.
CREATE INDEX idx_relatorio_entrega_em ON os_historico (ocorrido_em, os_id)
    WHERE status_novo = 'ENTREGUE';

CREATE INDEX idx_relatorio_criado_em ON ordens_servico (criado_em_utc)
    WHERE criado_em_utc IS NOT NULL;
