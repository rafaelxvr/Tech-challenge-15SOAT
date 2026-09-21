# Live staging environment

The staging environment is deployed and serving traffic. Everything below was
verified against the running system.

## Public entry point

| | |
| --- | --- |
| API Gateway | `https://qcm8l43flb.execute-api.us-east-1.amazonaws.com` |
| Health | `GET /health` returns `{"status":"UP"}` |
| Region | `us-east-1` |

The gateway is the only public surface. The cluster workload sits behind an
internal Application Load Balancer and the database has no public address.

## Platform

| Component | Identity |
| --- | --- |
| Kubernetes | EKS `oficina-phase3`, version 1.35, two managed node groups |
| Workload | `oficina-app` in namespace `oficina-staging`, behind an internal ALB target group |
| Scaling | HorizontalPodAutoscaler, 1 to 2 replicas, 60% CPU target |
| Database | RDS PostgreSQL 16.15, private, schema at Flyway version 8 |
| Serverless | `challenge`, `verification`, `authorizer` and `notification` Lambda functions |
| Observability | New Relic account 8521907, application `oficina-api-staging` |

## Authentication

Two independent identities reach the same gateway.

**Customer, by CPF.** `POST /api/auth/cpf/desafios` looks the customer up, stores a
hashed one-time code and mails it through SES. `POST /api/auth/cpf/verificar`
exchanges the code for an RS256 customer token.

**Staff, by credentials.** `POST /api/auth/login` returns an HS256 staff token
carrying the operator role.

Every other route is bound to a Lambda request authorizer that re-applies the
`phase3-v2` route contract. The contract is default-deny: a route that is not
explicitly granted is refused, and `POST /api/ordens-servico/email/atualizar-status`
is deliberately never exposed at the gateway.

## Verifying it yourself

```bash
BASE=https://qcm8l43flb.execute-api.us-east-1.amazonaws.com

# Public health
curl -s $BASE/health

# A protected route refuses anonymous and invalid callers
curl -s -o /dev/null -w '%{http_code}\n' $BASE/api/clientes
curl -s -o /dev/null -w '%{http_code}\n' $BASE/api/clientes -H 'Authorization: Bearer invalid'

# Staff sign-in, then the same route with the issued token
TOKEN=$(curl -s -X POST $BASE/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<staff email>","senha":"<staff password>"}' | jq -r .accessToken)
curl -s $BASE/api/clientes -H "Authorization: Bearer $TOKEN"

# Customer sign-in by CPF; the code is delivered by e-mail
curl -s -X POST $BASE/api/auth/cpf/desafios \
  -H 'Content-Type: application/json' -d '{"cpf":"<cpf>"}'
curl -s -X POST $BASE/api/auth/cpf/verificar \
  -H 'Content-Type: application/json' \
  -d '{"desafioId":"<id>","codigo":"<code>"}'
```

The first two protected calls return `401`. The third returns `200` with the
customer page.

## Deployment

Pushing to `develop` runs `APP staging rollout`, which tests the commit, builds
the image, publishes it to ECR under an immutable `staging-<sha>-<timestamp>` tag,
rolls the Deployment forward, waits for the new revision to become healthy and
then checks the public health endpoint. A failure at any point rolls the
Deployment back to its previous revision.

The running Deployment records which commit and which workflow run produced it:

```bash
kubectl get deployment oficina-app -n oficina-staging \
  -o jsonpath='{.metadata.annotations.oficina\.io/released-commit}'
```

## Seeded accounts

The `V2` seed account `admin@oficina.com` is deliberately deactivated during
database bootstrap, so its published development password cannot be used against
a deployed environment. Operator accounts are provisioned separately and their
credentials are never stored in this repository.
