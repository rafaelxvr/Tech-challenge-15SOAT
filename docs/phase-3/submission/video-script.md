# Phase 3 demonstration script — 14 minutes

This is a recording plan, not evidence that any cloud action ran. Record only the R4 results actually obtained. Hide credentials, OTP values, tokens, customer identifiers, account IDs, and private URLs. If a deployment or long-running check is shown from a prior captured record, state its real timestamp and say that the recording is time-compressed.

| Time | Chapter | Show and say |
| --- | --- | --- |
| 00:00–01:30 | Architecture | Show the four repository architecture documents and component diagram. State the cloud topology is a reviewed design until R4 acceptance records are PASS. |
| 01:30–05:30 | Authentication and orders | With synthetic data, demonstrate CPF challenge/negative/replay only if R4 ran. Show staff and customer authorization boundaries, protected order create/transition/read/decision, and the removed email-token mutation. If unavailable, show the prepared contract and say `NOT_RUN`. |
| 05:30–08:30 | CI/CD | Show the exact protected PR/pipeline/release record only if it exists. Explain that staging/production branches use reviewed immutable inputs. Do not trigger a pipeline merely for the recording. |
| 08:30–12:30 | Dashboards, traces, failure and recovery | Show live dashboards/traces/alerts only if R4 captured them. Otherwise show the R2 configuration and R4 recovery previews, saying they are local preparation. Explain outbox/DLQ recovery needs inspected IDs and explicit action. |
| 12:30–14:00 | Data, documentation and result | Show ER/RFC/ADR/API snapshots and the R4 evidence manifest. State each result exactly as PASS, FAIL, or NOT_RUN; distinguish a provider acceptance from SES inbox receipt. |

The five chapters total 840 planned seconds; this is not a measured video duration. Before recording, prepare synthetic records and redacted windows. After recording, measure the actual capture (maximum 900 seconds), check code/dashboard readability and audio, and inspect every frame for secrets/PII. Obtain the explicit publishing destination and reviewer-invitation instruction before publishing; a local file or pending invitation is not verified access.

## Presenter cues and evidence boundaries

1. **00:00–01:30:** introduce APP/FUN/K8S/DB ownership and the component diagram. State which boundaries are implemented in source and which require staged evidence. Foundation/RDS control-plane states alone do not prove APP/FUN deployment.
2. **01:30–05:30:** reserve the first two minutes for CPF challenge consumption, customer/staff token separation and replay/inactive-identity rejection; reserve the next two for order authorization, canonical transitions and atomic outbox behavior. Show actual approved demonstrations if available; otherwise label diagrams/tests as local source proof, not a live endpoint.
3. **05:30–08:30:** explain protected branch/environment assumptions, exact immutable artifacts, old-writer drain, migration/bootstrap gate and compatible rollback. Point to real run IDs/revisions only when captured. Any prerecorded deployment excerpt must display its actual timestamp and a time-compression disclosure; do not start a deployment just for the recording.
4. **08:30–12:30:** allocate two minutes to measured dashboards/traces if available, then two to a captured failure/recovery example. If absent, show configuration and recovery previews with `NOT_RUN` stated on-screen. Distinguish SQS delivery/SES acceptance from actual inbox delivery and keep replay actions read-only for this recording preparation.
5. **12:30–14:00:** connect relational invariants to the ER model/RFC/ADR and API snapshot revision; open the eight-record R4 manifest and state the actual outcomes. End the recording by identifying remaining acceptance/publication/access gaps, not by presenting the offline PDF as submitted.

## Local PDF runtime

Follow the [offline preparation guide](README.md) and pinned [runtime requirements](../../../scripts/submission/requirements-submission.txt). `--allow-template` renders the unfilled manifest as `NOT_READY / FIXTURE_ONLY`; `--allow-fixture` is reserved for synthetic test inputs. Neither mode creates submission-ready evidence or changes the measured-duration placeholder.
