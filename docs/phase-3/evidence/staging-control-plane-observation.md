# Redacted staging control-plane observation

Recorded: 2026-09-17. Scope: staging foundation and managed RDS only. Source: control-plane observations supplied by the coordinating task. This documentation task made no AWS calls; the original observation timestamps and durable provider-response links were not supplied here. This note is a redacted report, not an independently refreshed inventory or an R4 deployment receipt.

| Component | Reported observation | What it does not establish |
| --- | --- | --- |
| EKS cluster | `ACTIVE`; private endpoint reported | APP pod readiness, API reachability or application authorization. |
| EKS node groups | `ACTIVE` | Workload rollout, capacity under load or measured availability. |
| EKS add-ons | `ACTIVE` | Application/function integration or complete runtime configuration. |
| Application Load Balancer | `ACTIVE` | Healthy APP targets, route binding or successful business requests. |
| Managed PostgreSQL RDS | `available` | Schema migrations, bootstrap grants, runtime role denials or application connectivity. |

Account/resource identifiers, endpoint addresses, ARNs, credentials and raw provider payloads are omitted. No APP or FUN deployment is claimed. No production state, cost/window approval, release provenance or acceptance outcome is inferred from these control-plane states.

The [eight deployment records](manifest.json) remain `NOT_RUN`, with uncaptured revisions/digests and unavailable durable evidence links. The manifest spelling correction to `NOT_CAPTURED` changes no result. Keep this observation separate from the [R4 requirements/evidence matrix](requirements.md) and historical [cloud-window placeholder](cloud-window.md); completing acceptance still requires the reviewed release-specific evidence and checks.
