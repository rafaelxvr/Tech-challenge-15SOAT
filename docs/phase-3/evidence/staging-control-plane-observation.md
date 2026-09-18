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

The [eight deployment records](manifest.json) remain `NOT_RUN`: APP staging/production, K8S staging/production, FUN staging/production and DB staging/production. No APP deployment, production operation or R4 `PASS` is claimed from these observations. The observation must remain separate from the [R4 requirements/evidence matrix](requirements.md); completing acceptance still requires release-specific runtime evidence, screenshots and checks.

## Rehearsal update — 2026-09-18

The earlier attempt of K8S rehearsal run [35348411176](https://github.com/rafaelxvr/Tech-challenge-15SOAT-k8s-infra/actions/runs/35348411176) stopped fail-closed at the closed-cloud-window guard before S3 upload, CodeBuild or Terraform. After the cloud-window evidence was updated, its rerun completed the platform handoff with K8S CodeBuild build `oficina-phase3-oficina-k8s-infra-staging-deploy:5872a110-eec6-4282-a214-b2e2fbab9dd4`, a versioned promotion receipt at `releases/k8s/staging/promotions/d8eb79ab260c4b40a55b80199d9158d5e5ed1b77.json`, and successful foundation-addons; production was skipped.

This is K8S platform-handoff evidence only. It does not establish an APP build or deployment, healthy ALB targets, or successful API behavior: the observed `GET /health` remains HTTP `503`, the listed authentication paths remain HTTP `404`, and the internal ALB target health list remains empty. The eight R4 records and R5 submission/publishing status remain `NOT_RUN`.
