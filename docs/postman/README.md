# Coleção Postman da Fase 4 (core + billing + execution)

Arquivos em `postman/`:

| Arquivo | Uso |
|---|---|
| `Oficina-Fase4-Kind.postman_collection.json` | Coleção sequencial dos três serviços (Runner/Newman) |
| `kind-local.postman_environment.json` | Environment do Kind (URLs de port-forward, sem segredos) |
| `aws-academy.postman_environment.json` | Learner Lab (UNR-609, plano v9.2): sem NLB/ALB, entrada por `kubectl port-forward` em 127.0.0.1 (mesmas portas 8080/8081/8082); `customerTokenUrl` vazio (o emissor local não vale no Learner Lab) |

## 1. Importar

Importe a coleção e o environment `kind-local` no Postman (Import → arquivos) e selecione o environment.

## 2. Port-forward (somente 127.0.0.1)

```bash
kubectl port-forward --address 127.0.0.1 svc/<core>       8080:8080   # base .../api
kubectl port-forward --address 127.0.0.1 svc/oficina-billing   8081:8080   # base .../billing
kubectl port-forward --address 127.0.0.1 svc/oficina-execution 8082:8080   # base .../execucao
```

Os nomes de serviço/namespace do core dependem do bring-up do Kind; ajuste as URLs `baseUrlOs`, `baseUrlBilling` e `baseUrlExecution` se usar outras portas. Para a pasta 05.4, faça também port-forward do LocalStack (4566) e preencha `localstackUrl`.

## 3. Preencher segredos (somente localmente, nunca versionar)

Variáveis `secret` do environment, vazias no arquivo:

| Variável | O que é | Obrigatória |
|---|---|---|
| `adminSenha` | Senha do usuário `adminEmail` (staff ADMIN) no core | Sim (pasta 01) |
| `jwtSecret` | Segredo HMAC do JWT de staff do Kind (o mesmo do Secret `jwt_secret` do billing/execution). Com ele a coleção minta um token HS256 para billing/execution; sem ele, reaproveita o token do login do core | Só se billing/execution usarem issuer/audience/segredo próprios |
| `mpAccessToken` | Access Token **de teste** do Mercado Pago (sandbox) | Só na pasta 04 |

Variáveis comuns (não secretas): `adminEmail`, `jwtIssuer`/`jwtAudience`/`jwtKeyId` (padrão do overlay Kind), `filialId` (`MATRIZ`), `pagamentoModo` (`fake` ou `sandbox`), `executarMercadoPago` (`false`), `capacidadeReparos`, `pollMaxTentativas`/`pollIntervaloMs`, `customerTokenUrl`, `mailhogUrl` e `localstackUrl` (opcionais). As variáveis de fluxo (`token`, `tokenServicos`, `tokenCliente`, `osId*`, `pagamentoId*`, `execucaoId*`, `preferenceId`, `mpPaymentId` etc.) começam vazias e são preenchidas pelos scripts de pós-resposta. O sufixo da variável identifica o cenário: `A` fluxo feliz, `M` Mercado Pago, `E` estoque, `R` pagamento recusado, `C`/`F` capacidade/reparo recusado.

## 4. Ordem de execução

Rode as pastas em ordem no Collection Runner (ou Newman). Cada request tem testes de status, schema mínimo e estado esperado da Saga; estados assíncronos são consultados por poll (a mesma request se repete até o estado esperado ou `pollMaxTentativas`).

| Pasta | Conteúdo | Pré-requisito |
|---|---|---|
| 00 Saúde e readiness | health dos três serviços | serviços no ar |
| 01 Auth e token de staff | login ADMIN, token para billing/execution | `adminSenha` |
| 02 Cadastro | serviço, peça com estoque, peça sem estoque | 01 |
| 03 Fluxo feliz | OS → diagnóstico → orçamento → aprovação → pagamento simulado → execução → finalização/entrega | billing em `kind-fake`, `pagamentoModo=fake` |
| 04 Mercado Pago sandbox | preferência/checkout, **passo manual de pagar**, reconsulta, estorno | billing em `kind-sandbox`, `pagamentoModo=sandbox`, `executarMercadoPago=true`, `mpAccessToken` |
| 05 Falhas e compensações | 05.1 EstoqueInsuficiente, 05.2 pagamento recusado, 05.3 ReparoRecusado, 05.4 idempotência por `messageId` | `kind-fake`; 05.3 exige `capacidadeReparos=1` e `EXECUCAO_CAPACIDADE_REPAROS=1`; 05.4 exige `localstackUrl` e roda após a 03 |
| 06 Limpeza | desativa o cliente do fluxo feliz e limpa variáveis de fluxo | — |

Requests que não se aplicam (por exemplo a pasta 04 com `executarMercadoPago=false`) são puladas com `pm.execution.skipRequest()`.

## 5. Dois passos que precisam de atenção

- **Token do cliente (emissor local):** as rotas de CPF/OTP não existem no core; vêm do Lambda de `oficina-functions`, ausente no Kind. Para testar a aprovação do orçamento, suba o emissor local antes da coleção: `node scripts/postman/customer-token-minter.mjs` (porta 8099, `customerTokenUrl`). Ele gera um par RSA efêmero em memória (nada vai para disco) e imprime a chave pública. Configure no core, no ConfigMap `oficina-config`: `JWT_CUSTOMER_ISSUER=oficina-kind-customer`, `JWT_CUSTOMER_AUDIENCE=oficina-kind-api` e `SPRING_APPLICATION_JSON={"security":{"jwt":{"customer":{"public-keys":{"kind-local":"<PEM impresso>"}}}}}`, e reinicie o `oficina-app` (`kubectl rollout restart deploy/oficina-app` e aguarde `rollout status`: `envFrom` não atualiza pods existentes). Ao reiniciar o emissor, a chave muda: reaplique o ConfigMap e faça novo rollout do `oficina-app`. Encerre o emissor ao final (Ctrl+C).
  - O emissor só aceita `POST /token` com `Host` local, `Content-Type: application/json` e sem `Origin`; `sub` deve ser UUID minúsculo; `iat` sai 5 s no passado (tolera drift de relógio do pod).
  - **Escopo:** uso exclusivo do Kind local. Nunca aplique essa chave pública/issuer no `aws-academy` nem em ambiente compartilhado. Ao terminar, remova `SPRING_APPLICATION_JSON` e `JWT_CUSTOMER_*` do ConfigMap `oficina-config` e faça rollout, para o core não continuar confiando em chave órfã.
- **`pagamentoId`:** o billing atual não expõe consulta de pagamento por OS. A coleção tenta `GET /billing/pagamentos?osId=` (preenche sozinha se o endpoint existir). Enquanto não existir, preencha `pagamentoId<slot>` com o id lido do banco do billing, por exemplo:

  ```bash
  kubectl -n data exec deploy/postgres-billing -- psql -U postgres -d billing -t -A \
    -c "select id from pagamento where os_id = '<osId da variável>' order by criado_em desc limit 1"
  ```

  (confirme os nomes de tabela/coluna no schema do billing). Sem isso, o Newman não passa da aprovação na pasta 03/05.

## 6. Newman

```bash
npx newman run postman/Oficina-Fase4-Kind.postman_collection.json \
  -e postman/kind-local.postman_environment.json \
  --env-var adminSenha="$ADMIN_SENHA" \
  --folder "00 Saúde e readiness" --folder "01 Auth e token de staff" --folder "02 Cadastro" \
  --folder "03 Fluxo feliz (gateway-fake)" --folder "06 Limpeza" --reporters cli
```

Nunca anexe relatórios do Newman sem revisar: use `--reporters cli` ou remova cabeçalhos `Authorization` e bodies antes de compartilhar. A pasta 04 não grava payloads do Mercado Pago, só ids técnicos nas variáveis do environment (limpos na pasta 06).

## 6. Validação offline da coleção

`python scripts/postman/validate-collection.py` (requer PyYAML) confere, sem cluster: cada request dos três serviços contra os contratos versionados (`docs/openapi.yaml`, `docs/phase4/api/billing-openapi.json`, `docs/phase4/api/execution-openapi.json`), auth bearer nas rotas protegidas, ausência de valores em variáveis secretas e tokens, e a ordem das pastas e do fluxo feliz 03.1 a 03.14. Exceções documentadas no script: `POST /billing/simulacoes/pagamentos/{id}` (só no perfil `gateway-fake`) e `GET /billing/pagamentos?osId=` (consulta ainda inexistente; a coleção tolera 404). Os snapshots de billing (`585c786`) e execution (PR #45 do execution) devem ser atualizados quando os contratos mudarem.
