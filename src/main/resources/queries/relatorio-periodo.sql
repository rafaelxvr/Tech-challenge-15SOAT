-- Restrict candidate ORDERS, never the lifecycle rows fed to LEAD.
-- Delivered orders with no canonical delivery date are unassignable to a period:
-- expose them as exclusions in every period, without guessing from legacy timestamps.
WITH candidatos AS MATERIALIZED (
    SELECT os_id FROM os_historico
    WHERE status_novo = 'ENTREGUE' AND ocorrido_em >= :inicio AND ocorrido_em < :fim
    UNION
    SELECT os.id FROM ordens_servico os
    WHERE os.status = 'ENTREGUE' AND NOT EXISTS (
        SELECT 1 FROM os_historico h
        WHERE h.os_id = os.id AND h.status_novo = 'ENTREGUE' AND h.ocorrido_em IS NOT NULL
    )
), intervalos AS MATERIALIZED (
    SELECT h.*,
           LEAD(ocorrido_em) OVER cadeia AS proximo_em,
           LEAD(sequencia) OVER cadeia AS proxima_sequencia,
           LEAD(status_anterior) OVER cadeia AS proximo_anterior
    FROM os_historico h JOIN candidatos c ON c.os_id = h.os_id
    WINDOW cadeia AS (PARTITION BY h.os_id ORDER BY sequencia NULLS LAST)
), verificadas AS (
    SELECT os.id,
        COALESCE(os.historico_completo_desde_inicio AND os.criado_em_utc IS NOT NULL
          AND os.status = 'ENTREGUE'
          AND COUNT(i.id) = os.sequencia_historico
          AND MIN(i.sequencia) = 1 AND MAX(i.sequencia) = os.sequencia_historico
          AND COUNT(*) FILTER (WHERE i.status_novo = 'ENTREGUE') = 1
          AND BOOL_AND(COALESCE(
            i.sequencia IS NOT NULL AND i.ocorrido_em IS NOT NULL
            AND (i.sequencia <> 1 OR (i.status_anterior IS NULL
                AND i.status_novo = 'RECEBIDA' AND i.ocorrido_em = os.criado_em_utc))
            AND (i.sequencia = 1 OR (i.status_anterior, i.status_novo) IN (
                ('RECEBIDA'::status_os,'EM_DIAGNOSTICO'::status_os),
                ('EM_DIAGNOSTICO','AGUARDANDO_APROVACAO'),
                ('AGUARDANDO_APROVACAO','EM_DIAGNOSTICO'),
                ('AGUARDANDO_APROVACAO','EM_EXECUCAO'),
                ('EM_EXECUCAO','FINALIZADA'), ('FINALIZADA','ENTREGUE')))
            AND CASE WHEN i.sequencia = os.sequencia_historico THEN
                i.status_novo = 'ENTREGUE' AND i.proxima_sequencia IS NULL
                AND i.ocorrido_em >= :inicio AND i.ocorrido_em < :fim
            ELSE i.proxima_sequencia = i.sequencia + 1
                AND i.proximo_anterior = i.status_novo AND i.proximo_em > i.ocorrido_em
            END, FALSE)), FALSE) AS valida
    FROM candidatos c JOIN ordens_servico os ON os.id = c.os_id
    LEFT JOIN intervalos i ON i.os_id = os.id
    GROUP BY os.id
), por_ordem_status AS (
    SELECT i.os_id, i.status_novo, SUM(EXTRACT(EPOCH FROM (i.proximo_em-i.ocorrido_em))) AS total
    FROM intervalos i JOIN verificadas v ON v.id = i.os_id AND v.valida
    WHERE i.proximo_em IS NOT NULL
    GROUP BY i.os_id, i.status_novo
), duracoes AS (
    SELECT status_novo, SUM(total) AS total_segundos, COUNT(*) AS amostras
    FROM por_ordem_status GROUP BY status_novo
), contagens AS (
    SELECT COUNT(*) FILTER (WHERE valida) AS elegiveis,
           COUNT(*) FILTER (WHERE NOT valida) AS excluidas FROM verificadas
), criadas AS (
    SELECT COUNT(*) AS quantidade FROM ordens_servico
    WHERE criado_em_utc >= :inicio AND criado_em_utc < :fim
)
SELECT s.status::text, COALESCE(d.total_segundos,0) AS total_segundos,
       COALESCE(d.amostras,0) AS amostras, c.elegiveis, c.excluidas, n.quantidade AS criadas
FROM unnest(enum_range(NULL::status_os)) AS s(status)
LEFT JOIN duracoes d ON d.status_novo = s.status
CROSS JOIN contagens c CROSS JOIN criadas n
ORDER BY s.status
