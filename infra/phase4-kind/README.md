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

`postgres/grants-os.sql` roda como `oficina_migrator` depois de cada migration. Todas as tabelas (exceto `flyway_schema_history`) recebem apenas `SELECT/INSERT/UPDATE` (D15-R; auditoria no código: nenhum DELETE em runtime, as exclusões da API são desativações). `DELETE` só entra por tabela com evidência. O `DatabaseBootstrap` (caminho não Kind) ainda concede `DELETE` fora das tabelas saga; alinhar fica como follow-up.

## Bring-up único dos 3 serviços (os-service, billing, execution)

`scripts/phase4-kind-stack.ps1` cria UM cluster, sobe as dependências, builda as imagens dos SHAs pinados, carrega com `kind load` (sem ECR/registry), aplica os 3 serviços e espera readiness. SHAs padrão: os-service `cf34717`, billing `96c811a`, execution `1bf1e6c` (parâmetros `-OsSha/-BillingSha/-ExecutionSha`). O código de cada serviço é extraído com `git archive` do SHA, sem tocar no working tree.

```powershell
$b = 'D:epository\...\Tech-challenge-15SOAT-billing'; $e = 'D:epository\...\Tech-challenge-15SOAT-execution'   # checkouts com os SHAs
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Build  -BillingRepo $b -ExecutionRepo $e   # só imagens (sem cluster)
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Up     -BillingRepo $b -ExecutionRepo $e   # cluster + deps + 3 serviços (~5 min com cache frio)
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Verify -BillingRepo $b -ExecutionRepo $e   # readyReplicas, imagem por SHA e /actuator/health/readiness
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Down   -BillingRepo $b -ExecutionRepo $e   # kind delete cluster + prova de cleanup
```

- **Cluster parametrizável:** `-ClusterName` (ou `PHASE4_KIND_CLUSTER`) vale para todos os scripts `phase4-kind*.ps1`; o padrão é `oficina-phase4`. Para ensaiar sem tocar no cluster do runner, use outro nome (ex.: `-ClusterName oficina-p4-ensaio`). O `scripts/deploy-kind.sh` do billing (padrão `oficina-billing`) **não é usado** pelo bring-up, então não há conflito de cluster.
- **os-service:** overlay em `infra/phase4-kind/os-service/` (Service `oficina-os-service:8080`, contexto `/api`; imagem placeholder trocada pela tag do SHA). Migrações Flyway rodam num Job com `oficina_migrator`, seguidas de `grants-os.sql`; o runtime usa `oficina_app` com `SPRING_FLYWAY_ENABLED=false`. Saga ligada, endpoints SQS só do LocalStack, credenciais `test/test`.
- **billing:** overlay `kind-fake` do repositório (gateway de pagamento fake, só testes) + `billing-jwt-staff-patch.yaml`, que alinha emissor/audiência/kid do JWT de staff ao contrato do os-service/execution (o base do billing usa valores `*-kind`; sem o patch um token de staff não valeria nos três). O billing roda o próprio Flyway com `billing_migrator`. Service `oficina-billing:8080`, contexto `/billing`.
- **execution:** `k8s/local` do repositório. Service `oficina-execution:8080`, contexto `/execucao`.
- **Segredos:** gerados aleatoriamente por cluster e mantidos só em Secrets do Kubernetes. O `JWT_SECRET` HMAC é o mesmo nos três (o execution valida o token de staff emitido pelo os-service). O os-service também recebe uma chave pública RSA descartável de cliente (`oficina-os-customer-trust`); a privada não é guardada. Nada é impresso nem versionado.
- **Cleanup comprovado (`Down`):** apaga só o cluster nomeado e falha se restar cluster, container do Kind, `kubectl port-forward` do cluster ou imagem `oficina-phase4/*`. O bring-up não usa port-forward nem publica portas no host; o acesso é por `kubectl exec`/`port-forward` loopback sob demanda.
- Os logs do os-service são deliberadamente opacos (`"message":"safe"`). Para depurar uma falha de startup, monte um `logback.xml` temporário e use `-Dlogback.configurationFile` via `JAVA_OPTS` no cluster efêmero; não versione isso.

## Limites conhecidos

- **NetworkPolicy não é usada nem alegada.** O isolamento provado é por credenciais, roles e instâncias separadas. Ausência de egress para a AWS não foi bloqueada na rede; a garantia é por configuração: os scripts só falam com `localhost:4566` dentro do pod do LocalStack, limpam variáveis de perfil AWS e o contrato estático proíbe endpoints de serviço AWS reais.
- Os três serviços são implantados por `scripts/phase4-kind-stack.ps1` (seção abaixo). Billing e execution usam os manifests do próprio repositório no SHA pinado; só o os-service tem overlay aqui.
- Aceitação do Kind como evidência de deploy Kubernetes pelos avaliadores segue como risco aberto.
