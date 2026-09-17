# Canonical order reports

`GET /api/admin/relatorios/ordens?inicio=2026-09-15&fimExclusive=2026-09-16`
requires a staff ADMIN token. Missing, malformed, equal or reversed dates return
HTTP 400. The business zone is always `America/Sao_Paulo`; the interval is
inclusive at its start and exclusive at its end. For this example the UTC bounds
are `2026-09-15T03:00:00Z` and `2026-09-16T03:00:00Z`.

The current APP route matrix is `contracts/phase3-v2`. F3, I4 and I5 must vendor or
consume this additive version before the gateway exposes the report. The immutable
v1 contract remains unchanged and correctly denies the new route by default.

## Response meaning

The existing `ApiResponse` envelope contains `data` with `criadas`, `elegiveis`,
`excluidas` and a `duracoes` map containing every order status.

- `criadas`: orders whose canonical `criado_em_utc` falls inside the interval,
  counted independently of delivery. Legacy unknown creation times are never guessed.
- `elegiveis`: delivered orders with a delivery in the interval and a complete,
  verified canonical lifecycle. Every transition is examined, including those before
  the interval. Sequence gaps, status discontinuity, invalid transitions, tied or
  decreasing times, unknown rows, creation mismatch and aggregate-counter/status
  mismatch exclude the order.
- `excluidas`: candidate delivered orders that fail that validation. Delivered
  orders with no known canonical delivery timestamp are unassignable to any date;
  they appear as exclusions in **every** period. Do not sum these counts across
  adjacent periods. No legacy local timestamp is used to infer a delivery date.
- Each duration contains `totalSegundos`, `amostras` and `mediaSegundos`. Samples
  count orders that completed that status, not transitions. Repeated diagnosis
  intervals after refusal are summed per order first. The mean is a decimal string
  rounded to at most six fractional seconds; no samples is the literal `"N/A"`
  with numeric total and sample count both zero. ENTREGUE has no completed interval.

The reference fixture has A = 35/60/30 minutes and B = 25/40/10 minutes for
diagnosis/execution/finalization wait. The response means are `"1800"`, `"3000"`
and `"1200"` seconds (30/50/20 minutes), each with two order samples. Still-open
and incomplete delivered orders do not contribute to those means.

## Exporter boundary

`RelatoriosPort.consultar(LocalDate, LocalDate, ZoneId)` returns typed numeric
totals and sample counts, so R2 can export both without parsing API display strings.
`statusAtual(Instant)` returns per-status quantities, maximum known age in seconds,
age samples and unknown-age counts. This is a separate live projection and is not
restricted to the delivered cohort. A latest canonical event must match the order
counter/current status, not be in the future, and be later than older canonical
events. A legacy order can acquire a known current age after a new canonical event
without becoming eligible for complete-lifecycle averages. Null maximum age means
no known sample; zero means a measured zero.

Each call materializes a small SQL projection in its own read-only transaction,
with both a two-second transaction timeout and PostgreSQL `statement_timeout`.
Connections/cursors are released on return or failure. Exporters must make network
calls after the port returns, without wrapping those calls in another transaction.
The existing estimated-versus-real `MetricasService` report remains independent.

## Index evidence

V8 adds only a partial `(ocorrido_em, os_id)` delivery index and a partial canonical
creation-date index. PostgreSQL 16 `EXPLAIN (ANALYZE, BUFFERS)` on 20,000 delivered
orders / 120,000 transitions, with 20 deliveries in the period, measured the full
query at 34.720 ms before and 17.388 ms after the indexes. Shared buffers changed
from 4,464 hits to 1,161 hits + 102 reads. Both date predicates used index-only
scans. These are local synthetic measurements, not production latency guarantees.
`RelatorioPeriodoTest.explainRepresentativeData` reproduces the indexed plan in a
disposable real PostgreSQL database and writes `target/relatorio-explain.txt`.
Existing uniqueness constraints and foreign keys remain in force.
