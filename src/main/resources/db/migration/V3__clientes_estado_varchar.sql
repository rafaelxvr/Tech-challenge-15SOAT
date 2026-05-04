-- Hibernate valida String com JDBC VARCHAR; CHAR(2) no Postgres aparece como bpchar.
ALTER TABLE clientes
    ALTER COLUMN estado TYPE VARCHAR(2)
    USING (CASE WHEN estado IS NULL THEN NULL ELSE RTRIM(estado::text) END)::varchar(2);
