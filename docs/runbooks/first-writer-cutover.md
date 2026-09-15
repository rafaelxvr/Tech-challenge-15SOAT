# First canonical-history writer cutover (I6 prerequisite)

V6 is additive for stored data, but mixed old/new writers are unsupported. The first
deployment must drain writers before migrating. I6 must implement and exercise
this sequence in its migration Job, rollout script, and disposable Kind upgrade test.

1. Stop accepting mutations and drain in-flight requests and background writers.
   Scale the old application writers to zero and verify no old writer remains.
   For this first cutover use a controlled **Recreate** rollout; a normal rolling
   update can silently overwrite versioned aggregates or emit noncanonical history.
2. Record timestamp provenance. Fresh synthetic fixtures explicitly use **UTC**;
   set `HISTORICO_ZONA_COMPATIBILIDADE=UTC` for those installations. For an existing
   installation, establish the legacy writer's zone from deployment records and
   data provenance, and explicitly configure that zone. A workstation timezone is
   not evidence. If provenance is unknown, stop the live cutover until it is known;
   do not reinterpret or backfill old timestamps using a guessed zone.
3. Apply V6 once with the migration role after old writers are drained. Do not edit
   V1–V5. A migration failure prevents the new writer rollout. V6 preserves old
   timestamps, leaves canonical instants/sequences null, classifies populated
   staff references as STAFF and unrecorded actors as LEGACY_UNKNOWN, and marks
   existing orders incomplete. It removes only the order's old database-clock
   update trigger so new history and order compatibility times use the supplied
   application instant.
4. Start only the new writer, check fresh order creation and its initial sequence 1,
   customer/staff FKs, UTC canonical time, optimistic conflict 409, and transactional
   stock/history rollback. New transitions on an incomplete legacy order start the
   canonical counter at 1 without making the old history complete. Never roll back
   by starting an old binary against the migrated schema; a rollback requires an
   explicitly compatible writer or a reviewed database recovery procedure.
5. Resume mutations after readiness and smoke checks. Keep incomplete history
   excluded from complete-lifecycle reports until a separately reviewed,
   evidence-based reconciliation establishes both timezone and event order.

API compatibility: existing `criadoEm` history fields are preserved; the added
`ocorridoEm` is an ISO-8601 Instant with explicit UTC (`Z`) for new history and
absent/null for unresolved legacy history. New writes derive both from one Clock
instant. Canonical sequence, not a timestamp tie or UUID sort, defines new order.

This document is the application prerequisite for I6, not evidence that a cloud or
Kind rollout has already run.
