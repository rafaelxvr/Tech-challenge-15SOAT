# Fase 4 — Relatório consolidado de smokes por SHA (UNR-297, fatia 7 da UNR-69)

Cluster Kind efêmero `oficina-p4-unr69g` (nome exclusivo; o `oficina-phase4` do runner BDD não foi tocado). Sem egress AWS: só LocalStack com credenciais fictícias.

## Revisões implantadas

| Serviço | SHA | Imagem |
|---|---|---|
| os-service | `cf34717` | `oficina-phase4/os-service:cf34717` |
| billing | `96c811a` | `oficina-phase4/billing-service:96c811a` |
| execution | `1bf1e6c` | `oficina-phase4/execution-service:1bf1e6c` |

Scripts: `scripts/phase4-kind-stack.ps1` (Build/Up/Down) e `scripts/phase4-kind-smokes.ps1` (este relatório). Base: PR #76 (`unr-296-bdd-08`).

## Resultado

Estados e contagens esperados x observados, por verificação, na tabela de resultados abaixo. Resumo: 53 verificações, 51 PASS, 2 FAIL (mesmo achado F-1). A matriz BDD (34 PASS; 06h e 07c cobertos por teste unitário) está em [bdd-matriz.md](bdd-matriz.md) e não foi reexecutada nesta fatia.

## Reexecução após a correção do F-1 (UNR-299, 28/10)

Cluster Kind efêmero `oficina-p4-unr299` (removido ao final; o `oficina-phase4` do runner BDD não foi tocado). SHAs de `develop` após o merge dos PRs da UNR-299 (os #79, billing #6):

| Serviço | SHA | Imagem |
|---|---|---|
| os-service | `7a8ea6c` | `oficina-phase4/os-service:7a8ea6c` |
| billing | `17a37b9` | `oficina-phase4/billing-service:17a37b9` |
| execution | `1bf1e6c` (inalterado) | `oficina-phase4/execution-service:1bf1e6c` |

Resultado: **53 verificações, 53 PASS, 0 FAIL** (egress 8, fail-closed 6, isolamento 14, jwt 25). Verificações que antes falhavam:

| Id | Esperado | Antes (`cf34717` / `96c811a`) | Agora (`7a8ea6c` / `17a37b9`) |
|---|---|---|---|
| JWT-os-papel-errado | 403 | 401 | 403 PASS |
| JWT-billing-papel-errado | 403 | 401 | 403 PASS |

`JWT-execution-papel-errado` segue fixado em 401 (N-1, por desenho). A matriz BDD não foi reexecutada.

Ajuste no smoke: a verificação `FC-*-restaurado` tinha os SHAs antigos fixos no padrão da imagem e falhava com SHAs novos (falso FAIL na primeira tentativa desta reexecução, 51 PASS e 2 FAIL); agora usa `-OsSha`, `-BillingSha` e `-ExecutionSha`. Os defaults dos parâmetros continuam nos SHAs da fatia 7; passe os SHAs novos explicitamente.

## Achados

- **F-1 (os e billing): papel insuficiente devolve 401 em vez de 403. CORRIGIDO na UNR-299 (os #79, billing #6); smoke verde em `7a8ea6c`/`17a37b9`, ver a seção acima.** Registro original: MECANICO com token válido em rota só-ADMIN (`/api/admin/metricas/tempo-execucao-servicos` no os; `/billing/orcamentos` no billing) recebe 401. O mesmo token com ADMIN recebe 2xx, logo a assinatura, a validade e o papel do banco estão corretos; o que falha é o status da negação. Hipótese não confirmada: a negação vira `sendError(403)` e o redespacho para `/error` volta sem credencial (stateless) e cai no `authenticationEntryPoint`. O comportamento é visível só com HTTP real; testes MockMvc não o pegam. Cada serviço precisa de uma subtarefa com correção e teste de integração HTTP.
- **N-1 (execution): 403 por papel não é observável.** Todas as rotas aceitam ADMIN e MECANICO, e papel fora da lista é tratado como token inválido (401, por desenho). O smoke fixa 401 e registra a diferença do critério "papel errado → 403".
- **N-2: ausência de `JWT_SECRET` no execution.** O serviço não sobe, mas por `WeakKeyException` (chave padrão de 104 bits), não por validação explícita de segredo ausente. Resultado equivalente (fail-closed); o motivo é acidental.

## Cobertura das verificações

| Suíte | Cobre |
|---|---|
| `jwt` | por serviço: sem token, lixo, assinatura inválida, expirado, audiência errada → 401; ADMIN → 2xx; MECANICO em rota só-ADMIN → 403 esperado (F-1); MECANICO em leitura → 2xx |
| `isolamento` | role própria lê o próprio schema; roles cruzadas, senha cruzada, banco de manutenção e DDL negados; roles realmente conectadas por instância (`pg_stat_activity`); `DB_URL`/usuário por serviço; execution sem variável de banco SQL |
| `fail-closed` | `JWT_SECRET` removido do Deployment: o novo pod não fica pronto (`exitCode=1`); Deployment restaurado com `rollout undo` e imagem pinada |
| `egress` | nenhum host `amazonaws.com` em Deployments e ConfigMaps; endpoints SQS/DynamoDB só no LocalStack do cluster; credenciais AWS configuradas só `test`; logs dos três serviços sem `amazonaws.com`; nenhum Secret de aplicação com credencial AWS; controle positivo (o detector reconhece um host AWS de exemplo, para que "0 ocorrências" não seja vacuamente verdadeiro) |

## Resultados

| Suíte | Total | PASS | FAIL |
|---|---|---|---|
| egress | 8 | 8 | 0 |
| fail-closed | 6 | 6 | 0 |
| isolamento | 14 | 14 | 0 |
| jwt | 25 | 23 | 2 |

| Id | Verificação | Esperado | Observado | Status |
|---|---|---|---|---|
| JWT-os-sem-token | os GET /api/pecas com token sem-token | 401 | 401 | PASS |
| JWT-os-lixo | os GET /api/pecas com token lixo | 401 | 401 | PASS |
| JWT-os-assinatura-invalida | os GET /api/pecas com token assinatura-invalida | 401 | 401 | PASS |
| JWT-os-expirado | os GET /api/pecas com token expirado | 401 | 401 | PASS |
| JWT-os-audiencia-errada | os GET /api/pecas com token audiencia-errada | 401 | 401 | PASS |
| JWT-os-valido | os GET /api/pecas com token valido | 2xx | 2xx | PASS |
| JWT-os-papel-errado | os GET /api/admin/metricas/tempo-execucao-servicos com MECANICO (rota só ADMIN) | 403 | 401 | FAIL |
| JWT-os-admin-rota-admin | os GET /api/admin/metricas/tempo-execucao-servicos com ADMIN | 2xx | 2xx | PASS |
| JWT-os-mecanico-leitura | os GET /api/pecas com MECANICO | 2xx | 2xx | PASS |
| JWT-billing-sem-token | billing GET /billing/orcamentos?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token sem-token | 401 | 401 | PASS |
| JWT-billing-lixo | billing GET /billing/orcamentos?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token lixo | 401 | 401 | PASS |
| JWT-billing-assinatura-invalida | billing GET /billing/orcamentos?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token assinatura-invalida | 401 | 401 | PASS |
| JWT-billing-expirado | billing GET /billing/orcamentos?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token expirado | 401 | 401 | PASS |
| JWT-billing-audiencia-errada | billing GET /billing/orcamentos?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token audiencia-errada | 401 | 401 | PASS |
| JWT-billing-valido | billing GET /billing/orcamentos?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token valido | 2xx | 2xx | PASS |
| JWT-billing-papel-errado | billing GET /billing/orcamentos?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com MECANICO (rota só ADMIN) | 403 | 401 | FAIL |
| JWT-billing-admin-rota-admin | billing GET /billing/orcamentos?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com ADMIN | 2xx | 2xx | PASS |
| JWT-execution-sem-token | execution GET /execucao/execucoes?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token sem-token | 401 | 401 | PASS |
| JWT-execution-lixo | execution GET /execucao/execucoes?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token lixo | 401 | 401 | PASS |
| JWT-execution-assinatura-invalida | execution GET /execucao/execucoes?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token assinatura-invalida | 401 | 401 | PASS |
| JWT-execution-expirado | execution GET /execucao/execucoes?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token expirado | 401 | 401 | PASS |
| JWT-execution-audiencia-errada | execution GET /execucao/execucoes?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token audiencia-errada | 401 | 401 | PASS |
| JWT-execution-valido | execution GET /execucao/execucoes?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com token valido | 2xx | 2xx | PASS |
| JWT-execution-papel-errado | execution GET /execucao/execucoes?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com papel CLIENTE (fora de ADMIN/MECANICO) | 401 | 401 | PASS |
| JWT-execution-mecanico-leitura | execution GET /execucao/execucoes?osId=f787d3d9-4045-4f8a-8d67-1d1470978424 com MECANICO | 2xx | 2xx | PASS |
| DB-os-proprio | oficina_app lê o próprio schema (ordens_servico) | permitido | permitido | PASS |
| DB-billing-proprio | billing_app lê o próprio schema (pagamento) | permitido | permitido | PASS |
| DB-os-para-billing | oficina_app com a própria senha não entra no banco do billing | negado | negado | PASS |
| DB-billing-para-os | billing_app com a própria senha não entra no banco do os | negado | negado | PASS |
| DB-os-senha-cruzada | oficina_app com a senha do billing_app é rejeitada no os | negado | negado | PASS |
| DB-os-manutencao | oficina_app não entra no banco de manutenção postgres | negado | negado | PASS |
| DB-billing-manutencao | billing_app não entra no banco de manutenção postgres | negado | negado | PASS |
| DB-os-ddl | oficina_app (runtime) não cria tabela | negado | negado | PASS |
| DB-billing-ddl | billing_app (runtime) não cria tabela | negado | negado | PASS |
| DB-os-conectados | roles conectadas ao banco oficina (exceto superusuário de manutenção) | oficina_app | oficina_app | PASS |
| DB-billing-conectados | roles conectadas ao banco billing (exceto superusuário de manutenção) | billing_app | billing_app | PASS |
| DB-os-url | DB_URL do os-service | postgres-os/oficina/oficina_app | postgres-os/oficina/oficina_app | PASS |
| DB-billing-url | DB_URL do billing | postgres-billing/billing/billing_app | postgres-billing/billing/billing_app | PASS |
| DB-execution-sem-sql | execution não recebe variável de banco SQL | 0 | 0 | PASS |
| FC-os-sem-JWT_SECRET | os sem JWT_SECRET no Deployment: novo pod não fica pronto | nao-sobe | nao-sobe | PASS |
| FC-os-restaurado | os restaurado após o teste: JWT_SECRET de volta e imagem pinada | sim | sim | PASS |
| FC-billing-sem-JWT_SECRET | billing sem JWT_SECRET no Deployment: novo pod não fica pronto | nao-sobe | nao-sobe | PASS |
| FC-billing-restaurado | billing restaurado após o teste: JWT_SECRET de volta e imagem pinada | sim | sim | PASS |
| FC-execution-sem-JWT_SECRET | execution sem JWT_SECRET no Deployment: novo pod não fica pronto | nao-sobe | nao-sobe | PASS |
| FC-execution-restaurado | execution restaurado após o teste: JWT_SECRET de volta e imagem pinada | sim | sim | PASS |
| EG-detector | detector de host AWS reconhece um endpoint de exemplo | 1 | 1 | PASS |
| EG-sem-host-aws | nenhum Deployment/ConfigMap dos serviços referencia host público da AWS | 0 | 0 | PASS |
| EG-endpoints-localstack | todo endpoint SQS/DynamoDB é o LocalStack do cluster | localstack.infra | localstack.infra | PASS |
| EG-credenciais-ficticias | credenciais AWS configuradas são só o valor fictício "test" | test | test | PASS |
| EG-os-logs | logs do os sem tentativa de conexão a host público da AWS | 0 | 0 | PASS |
| EG-billing-logs | logs do billing sem tentativa de conexão a host público da AWS | 0 | 0 | PASS |
| EG-execution-logs | logs do execution sem tentativa de conexão a host público da AWS | 0 | 0 | PASS |
| EG-sem-secret-aws | nenhum Secret de aplicação carrega credencial AWS | 0 | 0 | PASS |

## Trace de teste

Execução em 2026-10-28 (UTC; smokes finais às 19:34), um `Up` e um `Down` por rodada, sempre no mesmo cluster:

```powershell
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Build -ClusterName oficina-p4-unr69g -BillingRepo <checkout> -ExecutionRepo <checkout>
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Up    -ClusterName oficina-p4-unr69g -BillingRepo <checkout> -ExecutionRepo <checkout>
$env:PHASE4_KIND_CLUSTER = 'oficina-p4-unr69g'
pwsh -NoProfile -Command "& ./scripts/phase4-kind-smokes.ps1 -Suite All -OutputDir <dir> -MarkdownPath <arquivo>"
pwsh -NoProfile -File scripts/phase4-kind-stack.ps1 -Action Down  -ClusterName oficina-p4-unr69g -BillingRepo <checkout> -ExecutionRepo <checkout>
```

- `Up`: `Up: OK`, três Deployments prontos com as imagens pinadas.
- Smokes: saída 1 (por F-1); o `smokes-results.json` completo ficou fora do repositório (temporário), ligado aos três SHAs.
- Ao longo do desenvolvimento do script houve execuções parciais anteriores com tokens mal formados (ex.: sem `sub` real do usuário no os-service, que usa o papel do banco como autoridade); a rodada acima é a final.
- O smoke de JWT cria e remove, só no cluster efêmero, um usuário MECANICO sintético (`smoke-mecanico@oficina.local`) no banco do os-service.
- O JWT de cada caso é mintado em memória com o segredo lido do cluster; nenhum token, segredo ou senha é impresso ou gravado. Senhas de role trafegam só por stdin.

## Evidência de cleanup

```
cluster oficina-p4-unr69g: deleted; containers=0 port-forwards=0 images-removed=3
```

`kind get clusters` depois do `Down` lista apenas `oficina-phase4` (do runner BDD, intacto); nenhum container `oficina-p4*`.

## Limites

- Não medido: cobertura, Sonar, CI (fatia 8, SRE). Egress é verificado por configuração e logs; não há `NetworkPolicy` bloqueando o tráfego, então a ausência de egress real não é provada por bloqueio de rede.
- Isolamento de banco do execution: sem credencial SQL; o acesso é DynamoDB no LocalStack e não foi testado acesso cruzado a outras tabelas.
