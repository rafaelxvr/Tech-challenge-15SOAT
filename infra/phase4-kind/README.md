# Phase 4 — dependências locais efêmeras (Kind)

Cluster descartável `oficina-phase4` com PostgreSQL 16 separados (OS e billing) e LocalStack (SQS FIFO + DynamoDB).
**Nenhum recurso AWS**: sem perfis, OIDC, ECR, Terraform apply ou endpoint de serviço AWS real.

| Componente | Endereço no cluster | Notas |
|---|---|---|
| Postgres OS | `postgres-os.data.svc.cluster.local:5432`, db `oficina` | roles `oficina_migrator` (dono do schema, roda Flyway) e `oficina_app` (runtime) |
| Postgres billing | `postgres-billing.data.svc.cluster.local:5432`, db `billing` | roles `billing_migrator` e `billing_app`; schema/grants do billing pertencem ao Plan 2 |
| LocalStack | `http://localstack.infra.svc.cluster.local:4566` | serviços `sqs,dynamodb`, credenciais `test/test` |

Imagens fixadas por digest nos manifests: `postgres:16.6-alpine`, `localstack/localstack:3.8`; Flyway `10.22-alpine` em `scripts/verify-phase4-db.ps1`.
Senhas de Postgres são aleatórias por cluster e vivem só em Secrets do Kubernetes (`postgres-*-admin`, `postgres-*-roles`, namespace `data`). Nada de segredo é versionado.

## Comandos (PowerShell 7)

```powershell
pwsh -NoProfile -File scripts/phase4-kind.ps1 -Action Up                 # cluster + Postgres + LocalStack
pwsh -NoProfile -File scripts/phase4-kind-init.ps1                       # roles + filas FIFO/DLQs + tabelas DynamoDB (idempotente)
pwsh -NoProfile -File scripts/phase4-kind.ps1 -Action Verify             # saúde + contrato SQS/DynamoDB
pwsh -NoProfile -File scripts/verify-phase4-db.ps1                       # Flyway V1..V10, upgrade V8->V10, grants, isolamento
pwsh -NoProfile -File scripts/phase4-kind.ps1 -Action PortForwardCheck   # prova listener só em 127.0.0.1
pwsh -NoProfile -File tests/phase4-kind-contract.ps1                     # contrato estático (sem cluster)
pwsh -NoProfile -File scripts/phase4-kind.ps1 -Action Down               # apaga somente o cluster oficina-phase4
```

Acesso a serviços: somente `kubectl port-forward --address 127.0.0.1`. O Kind não publica portas no host (sem `extraPortMappings`).

## Filas e tabelas (contrato do Plan 0 §8)

- FIFO `billing-commands.fifo`, `execution-commands.fifo`, `os-saga-replies.fifo` + DLQs `*-dlq.fifo` (`maxReceiveCount=5`, visibilidade 60 s, dedup por message group, throughput por message group, sem dedup por conteúdo) e `notifications.fifo`.
- DynamoDB: `oficina-phase4-execucoes` (GSIs `fila-index`, `os-index`), `oficina-phase4-execucao-outbox` (GSIs `estado-disponivel-index`, `os-sequencia-index`), `oficina-phase4-execucao-inbox`.

## Grants (D15/D16)

`postgres/grants-os.sql` roda como `oficina_migrator` depois de cada migration. Tabelas saga (`os_saga`, `os_saga_passo`, `saga_outbox`, `saga_inbox`) recebem apenas `SELECT/INSERT/UPDATE` (auditoria no código: nenhum DELETE). As demais tabelas seguem a paridade do `DatabaseBootstrap` (`SELECT/INSERT/UPDATE/DELETE`).

## Limites conhecidos

- **NetworkPolicy não é usada nem alegada.** O isolamento provado é por credenciais, roles e instâncias separadas. Ausência de egress para a AWS não foi bloqueada na rede; a garantia é por configuração: os scripts só falam com `localhost:4566` dentro do pod do LocalStack, limpam variáveis de perfil AWS e o contrato estático proíbe endpoints de serviço AWS reais.
- Os serviços de aplicação (os/billing/execution) ainda não são implantados aqui: isso é das tarefas de cada serviço (Task 3/5 do Plan 4).
- Aceitação do Kind como evidência de deploy Kubernetes pelos avaliadores segue como risco aberto.
