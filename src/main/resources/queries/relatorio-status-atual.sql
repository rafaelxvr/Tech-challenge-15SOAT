-- Current age is independent of the delivered lifecycle cohort. An incomplete
-- legacy lifecycle can still have a known, newly recorded canonical current status.
WITH idades AS (
    SELECT os.status,
           CASE WHEN h.sequencia = os.sequencia_historico AND h.status_novo = os.status
                AND h.ocorrido_em <= :agora
                AND NOT EXISTS (SELECT 1 FROM os_historico anterior
                    WHERE anterior.os_id = os.id AND anterior.sequencia < h.sequencia
                      AND anterior.ocorrido_em >= h.ocorrido_em)
           THEN EXTRACT(EPOCH FROM (:agora - h.ocorrido_em)) END AS idade
    FROM ordens_servico os
    LEFT JOIN LATERAL (
        SELECT sequencia,status_novo,ocorrido_em FROM os_historico
        WHERE os_id = os.id AND sequencia IS NOT NULL
        ORDER BY sequencia DESC LIMIT 1
    ) h ON TRUE
), agrupadas AS (
    SELECT status,COUNT(*) AS quantidade,MAX(idade) AS idade_maxima_segundos,
           COUNT(idade) AS amostras_idade,COUNT(*)-COUNT(idade) AS idades_desconhecidas
    FROM idades GROUP BY status
)
SELECT s.status::text,COALESCE(a.quantidade,0) AS quantidade,a.idade_maxima_segundos,
       COALESCE(a.amostras_idade,0) AS amostras_idade,
       COALESCE(a.idades_desconhecidas,0) AS idades_desconhecidas
FROM unnest(enum_range(NULL::status_os)) AS s(status)
LEFT JOIN agrupadas a ON a.status = s.status ORDER BY s.status
