# Phase 3 demonstration script — 14 minutes

This is a recording plan, not evidence that any cloud action ran. Record only the R4 results actually obtained. Hide credentials, OTP values, tokens, customer identifiers, account IDs, and private URLs. If a deployment or long-running check is shown from a prior captured record, state its real timestamp and say that the recording is time-compressed.

| Time | Chapter | Show and say |
| --- | --- | --- |
| 00:00–01:30 | Architecture | Show the four repository architecture documents and component diagram. State the cloud topology is a reviewed design until R4 acceptance records are PASS. |
| 01:30–05:30 | Authentication and orders | With synthetic data, demonstrate CPF challenge/negative/replay only if R4 ran. Show staff and customer authorization boundaries, protected order create/transition/read/decision, and the removed email-token mutation. If unavailable, show the prepared contract and say `NOT_RUN`. |
| 05:30–08:30 | CI/CD | Show the exact protected PR/pipeline/release record only if it exists. Explain that staging/production branches use reviewed immutable inputs. Do not trigger a pipeline merely for the recording. |
| 08:30–12:30 | Dashboards, traces, failure and recovery | Show live dashboards/traces/alerts only if R4 captured them. Otherwise show the R2 configuration and R4 recovery previews, saying they are local preparation. Explain outbox/DLQ recovery needs inspected IDs and explicit action. |
| 12:30–14:00 | Data, documentation and result | Show ER/RFC/ADR/API snapshots and the R4 evidence manifest. State each result exactly as PASS, FAIL, or NOT_RUN; distinguish a provider acceptance from SES inbox receipt. |

Before recording, verify the capture is at most 900 seconds, code/dashboard text is readable, and no secret/PII/window is visible. Obtain the explicit YouTube/Vimeo destination and reviewer-invitation instruction before publishing; a local file or pending invitation is not verified access.

## Local PDF runtime

The PDF builder accepts only ReportLab `4.2.5`, pinned in [`scripts/submission/requirements-submission.txt`](../../../scripts/submission/requirements-submission.txt). Create a project-local virtual environment, never a global install: `python -m venv .venv-submission`, then `./.venv-submission/Scripts/python -m pip install -r scripts/submission/requirements-submission.txt`. Render only a valid fixture with `--allow-fixture`; a verified submission manifest is a later user-owned publication/access input.
