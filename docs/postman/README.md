# Coleção Postman da Fase 4 (core + billing + execution)

Arquivos em `postman/`:

| Arquivo | Uso |
|---|---|
| `Oficina-Fase4-Kind.postman_collection.json` | Coleção sequencial dos três serviços (Runner/Newman) |
| `kind-local.postman_environment.json` | Environment do Kind (URLs de port-forward, sem segredos) |
| `aws-academy.postman_environment.json` | Placeholders para o deploy no Learner Lab (preencher depois) |

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

Variáveis comuns (não secretas): `adminEmail`, `jwtIssuer`/`jwtAudience`/`jwtKeyId` (padrão do overlay Kind), `filialId` (`MATRIZ`), `pagamentoModo` (`fake` ou `sandbox`), `executarMercadoPago` (`false`), `capacidadeReparos`, `pollMaxTentativas`/`pollIntervaloMs`, `mailhogUrl` e `localstackUrl` (opcionais). As variáveis de fluxo (`token`, `tokenServicos`, `tokenCliente`, `osId*`, `pagamentoId*`, `execucaoId*`, `preferenceId`, `mpPaymentId` etc.) começam vazias e são preenchidas pelos scripts de pós-resposta. O sufixo da variável identifica o cenário: `A` fluxo feliz, `M` Mercado Pago, `E` estoque, `R` pagamento recusado, `C`/`F` capacidade/reparo recusado.

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

- **Código do cliente (CPF):** o token do cliente exige o código de 6 dígitos enviado por e-mail. Informe `mailhogUrl` (a coleção lê o código da caixa do MailHog) ou, sem MailHog, rode a request "solicitar desafio", preencha `otpCodigo` e rode a seguinte.
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
  --env-var adminSenha="$ADMIN_SENHA" --env-var mailhogUrl=http://127.0.0.1:8025 \
  --folder "00 Saúde e readiness" --folder "01 Auth e token de staff" --folder "02 Cadastro" \
  --folder "03 Fluxo feliz (gateway-fake)" --folder "06 Limpeza" --reporters cli
```

Nunca anexe relatórios do Newman sem revisar: use `--reporters cli` ou remova cabeçalhos `Authorization` e bodies antes de compartilhar. A pasta 04 não grava payloads do Mercado Pago, só ids técnicos nas variáveis do environment (limpos na pasta 06).
