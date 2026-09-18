# Staging control-plane observation

Recorded: 2026-09-18. Scope: a read-only AWS control-plane observation of staging resources and service surfaces in `us-east-1`. No secret values were read and no AWS resources or settings were mutated. This is a control-plane observation, not a deployment receipt or R4 acceptance result.

| Component | Observed state | What it does not establish |
| --- | --- | --- |
| EKS cluster `oficina-phase3` | `ACTIVE`, Kubernetes version `1.35`; node groups `workers-a` and `workers-b` | Application pod readiness, API reachability or application authorization. |
| EKS API | Private and unreachable from the workstation for `kubectl` | In-cluster workload health, service-to-service connectivity or runtime acceptance. |
| Managed PostgreSQL RDS `oficina-phase3-staging-postgres` | `available`, PostgreSQL `16.15`, allocated storage `20 GiB` | Schema migrations, bootstrap grants, runtime role denials or application connectivity. |
| API Gateway `oficina-phase3-staging-http-api` | Endpoint `https://qcm8l43flb.execute-api.us-east-1.amazonaws.com`; `$default` stage with auto-deploy. Current routes: `POST /api/auth/login`, `POST /api/auth/cpf/verificar`, `POST /api/auth/cpf/desafios`, `GET /health` | Successful API behavior: `/health` returned HTTP `503`, and the authentication paths returned HTTP `404`. |
| Internal Application Load Balancer | `ACTIVE`; listener forwards to target group `oficina-phase3-staging-app` on port `8080`, readiness path `/api/actuator/health/readiness`; target health list was empty | Healthy APP targets, route binding or successful business requests. |
| Staging CodeBuild projects | APP project has no builds; FUN, DB and K8S projects have recent `SUCCEEDED` builds | A deployed application, release provenance, runtime readiness or end-to-end acceptance. |

The [eight deployment records](manifest.json) remain `NOT_RUN`: APP staging/production, K8S staging/production, FUN staging/production and DB staging/production. No deployment, production operation or R4 `PASS` is claimed from these observations. The observation must remain separate from the [R4 requirements/evidence matrix](requirements.md); completing acceptance still requires release-specific runtime evidence, screenshots and checks.
