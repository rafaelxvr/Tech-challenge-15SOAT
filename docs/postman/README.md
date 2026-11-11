# Coleção Postman da Fase 4 (core + billing + execution)

Arquivos em `postman/`:

| Arquivo | Uso |
|---|---|
| `Oficina-Fase4-Kind.postman_collection.json` | Coleção sequencial dos três serviços (Runner/Newman) |
| `kind-local.postman_environment.json` | Environment do Kind (URLs de port-forward, sem segredos) |
| `aws-academy.postman_environment.json` | Learner Lab (sessão 6, UNR-1217): sem NLB/ALB, entrada por `kubectl port-forward` em 127.0.0.1 nas portas **18080/18081/18082** (scripts da sessão 6); emissor JWT de staff `oficina-os-service` / `oficina-staff` / `staff-aws-1`; `customerTokenUrl` e `pagamentoResolverUrl` vazios (o emissor local e o resolvedor só valem no Kind) |
| `scripts/postman/customer-token-minter.mjs` | Emissor local do token de cliente (só Kind) |
| `scripts/postman/pagamento-id-resolver.mjs` | Resolvedor local do `pagamentoId` por `osId` (só Kind; o billing não tem esse GET) |

## 1. Importar

Importe a coleção e o environment `kind-local` no Postman (Import → arquivos) e selecione o environment.

## 2. Port-forward (somente 127.0.0.1)

```bash
kubectl port-forward --address 127.0.0.1 svc/<core>       8080:8080   # base .../api
kubectl port-forward --address 127.0.0.1 svc/oficina-billing   8081:8080   # base .../billing
kubectl port-forward --address 127.0.0.1 svc/oficina-execution 8082:8080   # base .../execucao
```

Os nomes de serviço/namespace do core dependem do bring-up do Kind (`scripts/phase4-kind-stack.ps1`: `oficina/oficina-os-service`, `oficina-billing/oficina-billing`, `oficina-execution/oficina-execution`); ajuste as URLs `baseUrlOs`, `baseUrlBilling` e `baseUrlExecution` se usar outras portas. Para a pasta 05.4, faça também port-forward do LocalStack (4566) e preencha `localstackUrl`.

**Learner Lab (environment `aws-academy`, sessão 6):** os scripts da sessão 6 (`bdd.mjs`) usam `kubectl -n <ns> port-forward --address 127.0.0.1 svc/<svc> <porta>:8080` com portas **18080** (`oficina/oficina-app`), **18081** (`oficina-billing/oficina-billing`) e **18082** (`oficina-execution/oficina-execution`), exatamente as URLs do environment `aws-academy`. Nenhuma credencial vai no arquivo: `adminSenha`, `jwtSecret` e `mpAccessToken` são `secret` e ficam vazios. Para billing/execution a coleção minta o token HS256 com `jwtSecret` (o segredo JWT do cluster, lido do estado privado da sessão, nunca colado em chat ou issue) e os claims `iss=oficina-os-service`, `aud=oficina-staff`, `kid=staff-aws-1`.

## 3. Preencher segredos (somente localmente, nunca versionar)

Variáveis `secret` do environment, vazias no arquivo:

| Variável | O que é | Obrigatória |
|---|---|---|
| `adminSenha` | Senha do usuário `adminEmail` (staff ADMIN) no core | Sim (pasta 01) |
| `jwtSecret` | Segredo HMAC do JWT de staff do Kind (o mesmo do Secret `jwt_secret` do billing/execution). Com ele a coleção minta um token HS256 para billing/execution; sem ele, reaproveita o token do login do core | Só se billing/execution usarem issuer/audience/segredo próprios |
| `mpAccessToken` | Access Token **de teste** do Mercado Pago (sandbox) | Só na pasta 04 |

Variáveis comuns (não secretas): `adminEmail`, `jwtIssuer`/`jwtAudience`/`jwtKeyId` (padrão do overlay Kind; no `aws-academy`, os da sessão 6), `filialId` (`MATRIZ`), `pagamentoModo` (`fake` ou `sandbox`), `executarMercadoPago` (`false`), `capacidadeReparos`, `pollMaxTentativas`/`pollIntervaloMs`, `customerTokenUrl`, `pagamentoResolverUrl`, `mailhogUrl` e `localstackUrl` (opcionais). As variáveis de fluxo (`token`, `tokenServicos`, `tokenCliente`, `osId*`, `pagamentoId*`, `execucaoId*`, `preferenceId`, `mpPaymentId` etc.) começam vazias e são preenchidas pelos scripts de pós-resposta. O sufixo da variável identifica o cenário: `A` fluxo feliz, `M` Mercado Pago, `E` estoque, `R` pagamento recusado, `C`/`F` capacidade/reparo recusado, `S` C2 real (Mercado Pago + EstoqueInsuficiente). Cada fluxo guarda o id da saga em `sagaId<slot>` (ver seção 5).

## 4. Ordem de execução

Rode as pastas em ordem no Collection Runner (ou Newman). Cada request tem testes de status, schema mínimo e estado esperado da Saga; estados assíncronos são consultados por poll (a mesma request se repete até o estado esperado ou `pollMaxTentativas`).

| Pasta | Conteúdo | Pré-requisito |
|---|---|---|
| 00 Saúde e readiness | health dos três serviços | serviços no ar |
| 01 Auth e token de staff | login ADMIN, token para billing/execution | `adminSenha` |
| 02 Cadastro | serviço, peça com estoque, peça sem estoque | 01 |
| 03 Fluxo feliz | OS → diagnóstico → orçamento (gerado pela saga) → aprovação → pagamento simulado → execução → finalização pela saga → entrega; **03.15** mostra o `sagaId` e a NRQL | billing em `kind-fake`, `pagamentoModo=fake` |
| 04 Mercado Pago sandbox | preferência/checkout, **passo manual de pagar**, reconsulta; **04.9 é apoio manual** (estorno direto na API do provedor), não o estorno do sistema | billing em `kind-sandbox`, `pagamentoModo=sandbox`, `executarMercadoPago=true`, `mpAccessToken` |
| 05 Falhas e compensações | 05.1 EstoqueInsuficiente, 05.2 pagamento recusado, 05.3 ReparoRecusado, 05.4 idempotência por `messageId` (todas com gateway-fake); **05.5 C2 real** (Mercado Pago sandbox + EstoqueInsuficiente, estorno automático, manual) | 05.1 a 05.4: `kind-fake`; 05.3 exige `capacidadeReparos=1` e `EXECUCAO_CAPACIDADE_REPAROS=1`; 05.4 exige `localstackUrl` e roda após a 03; 05.5: ver seção 5 |
| 06 Limpeza | desativa o cliente do fluxo feliz e limpa variáveis de fluxo | — |

Requests que não se aplicam (por exemplo a pasta 04 com `executarMercadoPago=false`) são puladas com `pm.execution.skipRequest()`.

## 5. Observabilidade (sagaId e NRQL) e C2 real

**sagaId e NRQL.** O primeiro request de saga de cada fluxo guarda o id em `sagaId<slot>` (`sagaIdA`, `sagaIdM`, `sagaIdE`, `sagaIdR`, `sagaIdC`, `sagaIdF`, `sagaIdS`). No fim de cada fluxo há um request de observabilidade (03.15, 04.10, 05.1i, 05.2g, 05.3n, 05.5m) que imprime no Console do Postman (e na aba Visualize) as consultas NRQL prontas para colar no New Relic, sem nenhuma chave no arquivo:

```
FROM Transaction SELECT count(*) AS transacoes, uniques(appName) AS servicos WHERE sagaId = '<sagaId>' FACET traceId SINCE 3 hours ago LIMIT MAX
FROM Transaction SELECT count(*) AS transacoes, uniqueCount(traceId) AS tracesDistintos, uniques(appName) AS servicos WHERE sagaId = '<sagaId>' SINCE 3 hours ago
FROM Transaction SELECT count(*) AS transacoes WHERE sagaId = '<sagaId>' FACET appName SINCE 3 hours ago LIMIT MAX
```

Critério de trace da Fase 4 (decidido pelo Rafael em 10/11): **um `trace.id` por etapa** (criação da OS, ações do mecânico, decisão do cliente, pagamento) e **a OS inteira correlacionada por `sagaId`**; não afirmar trace único da OS inteira. A ingestão do New Relic leva de 1 a 2 minutos. A prova do trace por etapa na AWS é `PENDENTE-AWS` (sessão 6).

**Preços em reais (V11).** Desde a `V11__corrigir_precos_semeados_para_reais.sql` o catálogo está em reais (ex.: Troca de Óleo R$ 80,00, Alinhamento R$ 120,00). A coleção valida o preço do serviço (entre R$ 0 e R$ 1.000), que o total do orçamento é a soma dos itens (serviço do catálogo mais peça) e que fica abaixo de R$ 10.000; na 05.5 valida também o valor estornado. Referência: Alinhamento (R$ 120,00) mais uma peça de R$ 50,00 = **R$ 170,00**, o valor cobrado no C1 da sessão 5. A coleção usa o primeiro serviço do catálogo, então o total do Kind (Troca de Óleo mais a peça de R$ 50,00) é R$ 130,00.

**C2 real (05.5, manual).** Mercado Pago sandbox + EstoqueInsuficiente: depois de pago, a OS pede uma peça sem estoque; o sistema compensa sozinho (estorna no provedor, OS `CANCELADA`) e a coleção só **verifica** o resultado: 05.5h/05.5i/05.5j (saga em compensação, `CANCELADA`, motivo registrado e sem compensações pendentes), 05.5k (reconsulta no billing até o pagamento constar `ESTORNADO`) e 05.5l (consulta final). Pré-requisitos: `pagamentoModo=sandbox`, `executarMercadoPago=true` e billing com Mercado Pago sandbox; `mpAccessToken` só para o apoio 04.9. O pagamento do checkout (05.5f) é manual, com a conta **compradora** de teste. **Não rode a 04.9 neste fluxo** (ela estorna direto no provedor e esconde o que o sistema faz). Todos os passos da 05.5 são pulados quando `executarMercadoPago` não é `true`, então o Newman do Kind com gateway-fake não chama o provedor. Esta pasta **não foi executada** contra o Mercado Pago nem contra a AWS (somente validada offline e pulada no Newman do Kind).

**Ordem de execução para o vídeo (Postman, no Runner ou request a request):**

1. 00 Saúde e readiness, 01 Auth e token de staff, 02 Cadastro.
2. Kind com gateway-fake (ensaio e prova local): 03 Fluxo feliz até a 03.15 (copie a NRQL), depois 05.1 (EstoqueInsuficiente) e 05.2.
3. Mercado Pago real (AWS, sob aprovação do Rafael): **05.5 C2 real**, a prova do estorno automático. A pasta 04 só se quiser mostrar o pagamento aprovado sem falha (C1); a 04.9 apenas como apoio.
4. Ao fim de cada fluxo, cole a NRQL do passo de observabilidade no New Relic e mostre um `trace.id` por etapa e a correlação por `sagaId`.
5. 06 Limpeza.

## 6. Dois passos que precisam de atenção

- **Token do cliente (emissor local):** as rotas de CPF/OTP não existem no core; vêm do Lambda de `oficina-functions`, ausente no Kind. Para testar a aprovação do orçamento, suba o emissor local antes da coleção: `node scripts/postman/customer-token-minter.mjs` (porta 8099, `customerTokenUrl`). Ele gera um par RSA efêmero em memória (nada vai para disco) e imprime a chave pública. Configure no core, no ConfigMap `oficina-config`: `JWT_CUSTOMER_ISSUER=oficina-kind-customer`, `JWT_CUSTOMER_AUDIENCE=oficina-kind-api` e `SPRING_APPLICATION_JSON={"security":{"jwt":{"customer":{"public-keys":{"kind-local":"<PEM impresso>"}}}}}`, e reinicie o `oficina-app` (`kubectl rollout restart deploy/oficina-app` e aguarde `rollout status`: `envFrom` não atualiza pods existentes). Ao reiniciar o emissor, a chave muda: reaplique o ConfigMap e faça novo rollout do `oficina-app`. Encerre o emissor ao final (Ctrl+C).
  - O emissor só aceita `POST /token` com `Host` local, `Content-Type: application/json` e sem `Origin`; `sub` deve ser UUID minúsculo; `iat` sai 5 s no passado (tolera drift de relógio do pod).
  - **Escopo:** uso exclusivo do Kind local. Nunca aplique essa chave pública/issuer no `aws-academy` nem em ambiente compartilhado. Ao terminar, remova `SPRING_APPLICATION_JSON` e `JWT_CUSTOMER_*` do ConfigMap `oficina-config` e faça rollout, para o core não continuar confiando em chave órfã.
- **`pagamentoId`:** o billing não expõe consulta de pagamento por OS (só `GET /pagamentos/{id}`). A coleção tenta `GET /billing/pagamentos?osId=` (preenche sozinha se o endpoint existir) e, no **Kind**, usa o resolvedor local: `KUBE_CONTEXT=kind-<cluster> node scripts/postman/pagamento-id-resolver.mjs` (porta 8098, `pagamentoResolverUrl` já preenchido no `kind-local`; lê só o `id` do pagamento no banco do billing, em loopback, e não devolve nem imprime a senha do Postgres). Encerre o resolvedor ao final (Ctrl+C).
  - **Learner Lab:** o RDS é privado e o resolvedor não vale. Preencha `pagamentoId<slot>` à mão (ex.: `pagamentoIdS` na 05.5) com o id técnico que o script da sessão 6 guarda no estado privado (`pagId` em `bdd-<cenário>.json`; é um UUID, não um segredo, mas fica fora do repositório). Sem isso a 04/05.5 não passa da aprovação; no vídeo, rode request a request e informe o id antes da 05.5d.

## 7. Newman

```bash
npx newman run postman/Oficina-Fase4-Kind.postman_collection.json \
  -e postman/kind-local.postman_environment.json \
  --env-var adminSenha="$ADMIN_SENHA" \
  --folder "00 Saúde e readiness" --folder "01 Auth e token de staff" --folder "02 Cadastro" \
  --folder "03 Fluxo feliz (gateway-fake)" --folder "05.1 EstoqueInsuficiente" --folder "05.2 Pagamento recusado" \
  --folder "06 Limpeza" --reporters cli
```

Com o emissor de cliente (`customer-token-minter.mjs`) e o resolvedor (`pagamento-id-resolver.mjs`) rodando, as pastas 03, 05.1, 05.2, 05.3 (com `capacidadeReparos=1`) e 05.4 (com `localstackUrl`) passam no Kind. A pasta 05.5 e a 04 ficam puladas sem `executarMercadoPago=true`. O `/actuator/health` do core retorna 503 no Kind porque o health de e-mail não tem SMTP; por isso os passos de saúde do core usam `/actuator/health/readiness`.

Nunca anexe relatórios do Newman sem revisar: use `--reporters cli` ou remova cabeçalhos `Authorization` e bodies antes de compartilhar. A pasta 04 não grava payloads do Mercado Pago, só ids técnicos nas variáveis do environment (limpos na pasta 06).

## 8. Validação offline da coleção

`python scripts/postman/validate-collection.py` (requer PyYAML) confere, sem cluster: cada request dos três serviços contra os contratos versionados (`docs/openapi.yaml`, `docs/phase4/api/billing-openapi.json`, `docs/phase4/api/execution-openapi.json`), auth bearer nas rotas protegidas, ausência de valores em variáveis secretas e tokens, a ordem das pastas e do fluxo feliz 03.1 a 03.15, o `sagaId` e o passo NRQL de cada fluxo e a guarda `executarMercadoPago` em todos os passos da 05.5. Exceções documentadas no script: `POST /billing/simulacoes/pagamentos/{id}` (só no perfil `gateway-fake`) e `GET /billing/pagamentos?osId=` (consulta ainda inexistente; a coleção tolera 404). Os snapshots de billing (`585c786`) e execution (PR #45 do execution) devem ser atualizados quando os contratos mudarem.
