# Roteiro do vídeo — Fase 4 (alvo 14min30s, limite 15min)

Roteiro para ensaio e gravação. Documento principal: [`entrega-fase-4.md`](entrega-fase-4.md).

**Integrantes:** Rafael Xavier (RM371756) e Silas Furini (RM374087).

## Regras

- Duração-alvo total: **14min30s**, com 30s de margem até o limite de 15min.
- Ambiente da gravação: **AWS Academy**. O Kind aparece apenas como validação local e de CI antes do deploy, nunca como ambiente gravado.
- Vídeo gravado e entregue: <https://share.descript.com/view/UGNJAf9yzG6>. As evidências do AWS Academy (11/11/2026) estão na seção 10 do documento de entrega.
- Estado das evidências no AWS Academy (execução de 11/11/2026, seção 10): caminho feliz (C1) aprovado (saga `CONCLUIDA`, OS `ENTREGUE`, R$ 130,00); trace por etapa atendido nos dois fluxos; **estorno real (C2) falhou** (HTTP 401 do Mercado Pago sandbox, saga `FALHA_COMPENSACAO`, defeito aberto); ReparoRecusado (C3) não executado no AWS Academy, provado só no Kind. Não afirmar na fala o que não foi provado.
- Plano B de cada bloco: evidência **pré-capturada ligada ao mesmo SHA**, apresentada como tal. Nunca apresentar fixture ou captura antiga como execução ao vivo.
- Não exibir console com tokens, variáveis secretas, payloads integrais, PII, ARN ou IDs de recursos. Credenciais de Mercado Pago e New Relic ficam fora de banda.

## Como abrir cada recurso

Em cada bloco, a linha **Abrir** diz o que aparece na tela, com repositório, path exato a partir da raiz e link. Repositórios: `Tech-challenge-15SOAT` (core), `Tech-challenge-15SOAT-billing` e `Tech-challenge-15SOAT-execution`.

- **Arquivo no GitHub:** link fixado na tag `fase4-entrega-2026-11-12` (`https://github.com/rafaelxvr/<repositório>/blob/fase4-entrega-2026-11-12/<path>`). Do core há também o caminho local de leitura em `D:\repository\fiap-entrega\<path>`; billing e execution só têm o link.
- **Versão final só em `develop`:** `docs/entrega-fase-4.pdf`, `docs/postman/README.md` e os arquivos de `postman/` têm a versão final apenas em `develop` até a tag ser movida; nesses itens o link usa `develop` (o caminho local, que está em `develop`, é o mesmo).
- **PDF de entrega** (`docs/entrega-fase-4.pdf`, 10 páginas): capa na p. 1; seção 2.1 na p. 2; seções 2.2 e 2.3 na p. 3; seções 3.2 a 3.4 nas pp. 3 e 4; seções 5.1 na p. 5 e 5.2 e 6 na p. 6; seções 7 e 8 na p. 7; seção 9 na p. 8; seção 10 na p. 9 e seção 11 na p. 10.
- **Postman:** coleção `postman/Oficina-Fase4-Kind.postman_collection.json` e environment `postman/aws-academy.postman_environment.json`, ambos no core. Nomes de pasta e de request exatamente como aparecem no Postman.
- **Terminal:** pasta de execução e comando exato; nada que imprima segredo.
- **Verificação:** `python scripts/check-roteiro-paths.py` confere que cada path citado existe no repositório indicado, na tag ou branch do link.

## Grade

| # | Janela | Duração | Conteúdo |
|---|---|---|---|
| 1 | 00:00–00:45 | 0:45 | Abertura e escopo |
| 2 | 00:45–02:15 | 1:30 | Repositórios, SHAs e arquitetura |
| 3 | 02:15–03:45 | 1:30 | Contratos, filas, inbox/outbox e Saga |
| 4 | 03:45–05:45 | 2:00 | Caminho feliz |
| 5 | 05:45–07:45 | 2:00 | EstoqueInsuficiente e compensação |
| 6 | 07:45–09:45 | 2:00 | ReparoRecusado e compensação |
| 7 | 09:45–11:15 | 1:30 | Pagamento sandbox e estorno |
| 8 | 11:15–12:45 | 1:30 | CI, cobertura, Sonar e New Relic |
| 9 | 12:45–13:45 | 1:00 | DLQ/redrive e reconciliação |
| 10 | 13:45–14:30 | 0:45 | Limitações e encerramento |

Soma: 0:45 + 1:30 + 1:30 + 2:00 + 2:00 + 2:00 + 1:30 + 1:30 + 1:00 + 0:45 = **14:30**.

## Blocos

### 1. Abertura e escopo (00:00–00:45)
- **Tela/ação:** capa do documento de entrega; fala de abertura.
- **Abrir:** `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · capa (p. 1).
- **Prova:** identificação da entrega, com os integrantes e RMs.
- **Evidência esperada:** capa com integrantes e os três repositórios.
- **Plano B:** imagem estática da capa do PDF.

### 2. Repositórios, SHAs e arquitetura (00:45–02:15)
- **Tela/ação:** três repositórios no GitHub; tabela de SHAs e digests de `main` (capa; `sourceSha` e digest provam a origem da imagem; a entrega é a tag `fase4-entrega-2026-11-12`); diagrama da seção 2.1; bancos isolados (SQL e NoSQL).
- **Abrir (nesta ordem):**
  1. repositórios, na tag da entrega: [core](https://github.com/rafaelxvr/Tech-challenge-15SOAT/tree/fase4-entrega-2026-11-12), [billing](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/tree/fase4-entrega-2026-11-12) e [execution](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/tree/fase4-entrega-2026-11-12);
  2. `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · capa (p. 1), tabela de SHAs e digests;
  3. `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · seção 2.1 "Diagrama geral" (p. 2);
  4. `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · seção 2.2 "Bancos isolados" (p. 3);
  5. `Tech-challenge-15SOAT` · `infra/aws-academy/rds.tf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/infra/aws-academy/rds.tf) · local `D:\repository\fiap-entrega\infra\aws-academy\rds.tf` (bancos SQL) e `Tech-challenge-15SOAT` · `infra/aws-academy/dynamodb.tf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/infra/aws-academy/dynamodb.tf) · local `D:\repository\fiap-entrega\infra\aws-academy\dynamodb.tf` (banco NoSQL);
  6. `Tech-challenge-15SOAT` · `infra/aws-academy/release-manifest.json` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/infra/aws-academy/release-manifest.json) · local `D:\repository\fiap-entrega\infra\aws-academy\release-manifest.json` (SHAs e digests fixados).
- **Prova:** serviços independentes e bancos isolados.
- **Evidência esperada:** SHAs exibidos coincidem com os da capa no dia da gravação (reconferir antes). Baseline de `main` de 11/11/2026:
  - core `d9b605bd445a2442af09cad9cdbeee4f4542f4f9`, digest `sha256:704d51aa1f75dc3bce6943768de42d1a8021c69c29fe3b324c09cf2896eb1aa3`, [run 42953101412](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42953101412);
  - billing `140dba5dd782f985ce2d6b5e07acb5aed57114b1`, digest `sha256:bb97e1295bc5a33b65839d61f4338c84cdc7f47340aeb59cce0837b6a2368e94`, [run 42953106505](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/actions/runs/42953106505);
  - execution `fe2d8844c6172e3215603d9f97f5da64be22fc3e`, digest `sha256:e3832274dc29b5e32174bdffeec4c2e50bd05d481107ec8770d964e16fb3af18`, [run 42953113662](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/actions/runs/42953113662).
  - A tabela prova SHA, digest e CI; não prova deploy no AWS Academy dos digests finais.
- **Plano B:** captura da tabela de SHAs e digests com data e hora.

### 3. Contratos, filas, inbox/outbox e Saga (02:15–03:45)
- **Tela/ação:** JSON Schemas dos contratos, filas FIFO e DLQs, ADR 006, tabela de compensações (seção 3.3).
- **Abrir (nesta ordem):**
  1. contratos (um JSON Schema por mensagem, versão 1): `Tech-challenge-15SOAT-billing` · `contracts/GerarOrcamento/v1.json` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/blob/fase4-entrega-2026-11-12/contracts/GerarOrcamento/v1.json); hashes: `Tech-challenge-15SOAT-billing` · `contracts/CONTRACTS.sha256` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/blob/fase4-entrega-2026-11-12/contracts/CONTRACTS.sha256); o execution tem o mesmo conjunto: `Tech-challenge-15SOAT-execution` · `contracts/IniciarReparo/v1.json` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/blob/fase4-entrega-2026-11-12/contracts/IniciarReparo/v1.json);
  2. envelope com `sagaId`, `causationId` e `traceparent`: `Tech-challenge-15SOAT` · `src/main/java/com/oficina/saga/application/mensagem/Envelope.java` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/src/main/java/com/oficina/saga/application/mensagem/Envelope.java) · local `D:\repository\fiap-entrega\src\main\java\com\oficina\saga\application\mensagem\Envelope.java`;
  3. filas FIFO e DLQs (`billing-commands`, `execution-commands`, `os-saga-replies`, `maxReceiveCount` 5): `Tech-challenge-15SOAT` · `infra/aws-academy/messaging.tf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/infra/aws-academy/messaging.tf) · local `D:\repository\fiap-entrega\infra\aws-academy\messaging.tf`;
  4. inbox e outbox: `Tech-challenge-15SOAT` · `src/main/java/com/oficina/saga/adapter/out/persistence/JdbcInbox.java` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/src/main/java/com/oficina/saga/adapter/out/persistence/JdbcInbox.java) · local `D:\repository\fiap-entrega\src\main\java\com\oficina\saga\adapter\out\persistence\JdbcInbox.java` e `Tech-challenge-15SOAT` · `src/main/java/com/oficina/saga/adapter/out/persistence/JdbcSagaOutbox.java` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/src/main/java/com/oficina/saga/adapter/out/persistence/JdbcSagaOutbox.java) · local `D:\repository\fiap-entrega\src\main\java\com\oficina\saga\adapter\out\persistence\JdbcSagaOutbox.java`;
  5. ADR 006, saga orquestrada: `Tech-challenge-15SOAT` · `docs/adrs/006-orchestrated-saga.md` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/docs/adrs/006-orchestrated-saga.md) · local `D:\repository\fiap-entrega\docs\adrs\006-orchestrated-saga.md`;
  6. `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · seção 3.3 "Compensações" (p. 4).
- **Prova:** mensageria e orquestração.
- **Evidência esperada:** envelope com `sagaId`, `causationId`, `traceparent`; sem payload real.
- **Plano B:** páginas do documento de entrega.

### 4. Caminho feliz (03:45–05:45)
- **Tela/ação:** abrir OS, diagnóstico, orçamento, aprovação, pagamento, reserva, reparo, finalização; consultar `GET /api/ordens-servico/{id}/saga` a cada etapa.
- **Abrir:**
  1. `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · seção 3.2 "Fluxo feliz" (pp. 3 e 4), para a ordem dos estados;
  2. terminal, pasta `D:\unravel-private\aws-learner-lab\sessao6`: `$env:KUBECONFIG = "$PWD\kubeconfig"` e `kubectl get pods -A` (pods dos três serviços `Running`); depois `node bdd.mjs start C1`, pagar o checkout fora da tela e `node bdd.mjs finish C1`;
  3. Postman, pasta **04 Mercado Pago sandbox real**: requests "04.1 - Abrir OS", "04.1 - Saga: criada e aguardando diagnóstico", "04.1 - Saga: avançou após diagnóstico", "04.1 - Saga: aguardando decisão do cliente", "04.4 - Saga: aguardando pagamento" (cada "Saga:" é o `GET /api/ordens-servico/{id}/saga`). Coleção: `Tech-challenge-15SOAT` · `postman/Oficina-Fase4-Kind.postman_collection.json` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/postman/Oficina-Fase4-Kind.postman_collection.json) · local `D:\repository\fiap-entrega\postman\Oficina-Fase4-Kind.postman_collection.json`. Environment: `Tech-challenge-15SOAT` · `postman/aws-academy.postman_environment.json` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/postman/aws-academy.postman_environment.json) · local `D:\repository\fiap-entrega\postman\aws-academy.postman_environment.json`.
- **Prova:** conclusão da OS (`FINALIZADA`).
- **Evidência esperada:** saga encerrada com sucesso; estados na ordem da seção 3.2.
- **Plano B:** BDD-01a do [run 40884415348](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/40884415348), pré-capturado (validação local em Kind com SHAs anteriores; dizer isso na fala). Também existe o caminho feliz aprovado em execução anterior no AWS Academy (saga `CONCLUIDA`, OS `FINALIZADA`, R$ 170,00); citar como resultado dessa execução, não como execução nos digests finais. Execução ao vivo nos digests finais: a informar.

### 5. EstoqueInsuficiente e compensação (05:45–07:45)
- **Tela/ação:** OS com peça sem saldo; seguir até a reserva de peças.
- **Abrir:**
  1. terminal, pasta `D:\unravel-private\aws-learner-lab\sessao6`: `node bdd.mjs start C2` (imprime o `sagaId`), pagar o checkout fora da tela, `node bdd.mjs finish C2`;
  2. Postman (coleção e environment do bloco 4), pasta **05 Falhas e compensações** → **05.5 C2 real: Mercado Pago sandbox + EstoqueInsuficiente, estorno automático (manual, exige executarMercadoPago=true)**: requests "05.5h - Saga: EstoqueInsuficiente dispara compensação", "05.5i - Saga: compensação termina em CANCELADA" e "05.5j - Saga: motivo de falha registrado, sem compensações pendentes";
  3. `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · seção 3.3 "Compensações" (p. 4).
- **Prova:** falha/compensação 1.
- **Passos demonstráveis:**
  1. evento/motivo de falha `ESTOQUE_INSUFICIENTE` registrado na saga;
  2. transição da saga para compensação;
  3. compensações em ordem inversa: estorno do pagamento, cancelamento do orçamento, cancelamento da OS;
  4. estado final observável: OS `CANCELADA`, orçamento cancelado, estorno registrado, estoque inalterado.
- **Evidência esperada:** log de passos da saga (`/saga`) com os quatro itens acima.
- **Plano B:** cenário correspondente da matriz BDD do run 40884415348, ligado ao seu SHA. Na execução anterior no AWS Academy, o `EstoqueInsuficiente` funcionou, mas o estorno falhou (`MERCADO_PAGO_ESTORNO_FALHOU`, saga `FALHA_COMPENSACAO`); a correção está em `main`. O passo 3 (estorno) só pode ser mostrado como funcionando após a repetição no AWS Academy com os digests finais. Antes disso, dizer que o estorno falhou e foi corrigido, sem prova no AWS Academy.

### 6. ReparoRecusado e compensação (07:45–09:45)
- **Tela/ação:** forçar capacidade cheia/filial inválida no Execution e iniciar o reparo.
- **Abrir:**
  1. terminal, pasta `D:\unravel-private\aws-learner-lab\sessao6`: `node bdd.mjs start C3` (altera a configuração de filiais do Execution e imprime o `sagaId`), pagar o checkout fora da tela, `node bdd.mjs finish C3` (restaura a configuração; se abortar, `node bdd.mjs restore C3`);
  2. cenário equivalente no Kind (plano B), Postman (coleção e environment do bloco 4), pasta **05 Falhas e compensações** → **05.3 ReparoRecusado (exige capacidadeReparos=1)**: requests "05.3g - Execução C: iniciar e NÃO concluir (ocupa a capacidade)" e "05.3m - Saga: ReparoRecusado dispara compensação";
  3. `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · seção 3.3 "Compensações" (p. 4).
- **Prova:** falha/compensação 2 (pivô da saga).
- **Passos demonstráveis:**
  1. `ReparoRecusado` retornado pelo Execution (motivo `REPARO_RECUSADO`);
  2. transição da saga para compensação;
  3. compensações: repor estoque, estornar pagamento, cancelar orçamento, cancelar OS;
  4. estado final observável: OS `CANCELADA`, estoque reposto, estorno registrado, fila de execução sem item.
- **Evidência esperada:** log de passos da saga e estado do estoque antes/depois.
- **Plano B:** cenário "reparo recusado por capacidade cheia" da matriz BDD do run 40884415348. Execução no AWS Academy: a informar.

### 7. Pagamento sandbox e estorno (09:45–11:15)
- **Abrir:**
  1. coleção: `Tech-challenge-15SOAT` · `postman/Oficina-Fase4-Kind.postman_collection.json` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/postman/Oficina-Fase4-Kind.postman_collection.json) · local `D:\repository\fiap-entrega\postman\Oficina-Fase4-Kind.postman_collection.json`; environment: `Tech-challenge-15SOAT` · `postman/aws-academy.postman_environment.json` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/postman/aws-academy.postman_environment.json) · local `D:\repository\fiap-entrega\postman\aws-academy.postman_environment.json`; ordem de execução: `Tech-challenge-15SOAT` · `docs/postman/README.md` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/postman/README.md) · local `D:\repository\fiap-entrega\docs\postman\README.md` (seção 5 "Observabilidade (sagaId e NRQL) e C2 real");
  2. Postman, pasta **05 Falhas e compensações** → **05.5 C2 real: Mercado Pago sandbox + EstoqueInsuficiente, estorno automático (manual, exige executarMercadoPago=true)**: "05.5a - Abrir OS", "05.5e - Billing: extrair checkoutUrl e preferenceId", "05.5f - MANUAL: pague o checkout e só então rode 05.5g", "05.5g - Billing: reconsultar pagamento no provedor (repete até aprovar)", "05.5k - Billing: reconsulta até o pagamento constar ESTORNADO (estorno feito pelo sistema; a reconsulta só lê o provedor)", "05.5l - Billing: pagamento estornado (consulta final)" e "05.5m - Observabilidade: NRQL do sagaId (New Relic)";
  3. terminal, pasta `D:\unravel-private\aws-learner-lab\sessao6`: `node pagamento-id.mjs C2 --clip` (copia o `pagamentoId` para a área de transferência, sem imprimir), antes da 05.5d.
- **Tela/ação:** Postman com a coleção `postman/Oficina-Fase4-Kind.postman_collection.json` ([PR #85](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/85)) e o environment `aws-academy` (portas 18080/18081/18082; segredos só no Postman local): preferência, pagamento sandbox, reconsulta e **estorno automático do sistema**.
- **Ordem de execução no Postman (detalhe em `docs/postman/README.md`, seção 5):** (1) pastas 00, 01 e 02; (2) **05.5 C2 real**: OS com peça sem estoque, pagamento manual do checkout com a conta compradora (05.5f), reconsulta (05.5g), saga `CANCELADA` e pagamento `ESTORNADO` verificado no billing (05.5k/05.5l), **sem** chamar a 04.9 (ela estorna direto no provedor e serve só de apoio); (3) se houver tempo, a pasta 04 como C1 (pagamento aprovado, sem falha); (4) ao fim de cada fluxo, o passo de observabilidade (05.5m, 04.10, 03.15) imprime o `sagaId` e a NRQL pronta (`WHERE sagaId = '<id>'`, `FACET traceId`) para colar no New Relic; (5) pasta 06. O ensaio com gateway fake (03, 05.1, 05.2) é só prova local no Kind. Os passos 05.5 e 04 são manuais e exigem `executarMercadoPago=true`.
- **`pagamentoId` no AWS Academy:** o billing não tem GET por `osId` e o banco é privado; informe `pagamentoIdS` à mão antes da 05.5d, ou rode o request a request.
- **Prova:** integração Mercado Pago.
- **Evidência esperada:** pagamento confirmado por reconsulta e estorno concluído; sem tokens, `external_reference` truncado, sem payload integral.
- **Preços em reais (V11):** o catálogo exibe valores em reais (ex.: Alinhamento R$ 120,00; Troca de Óleo R$ 80,00), após a `V11__corrigir_precos_semeados_para_reais.sql`. Antes da correção, um checkout de teste cobrou R$ 17.000 onde o esperado era R$ 170 (centavos gravados em colunas de reais pelo seed V2). A cobrança correta (R$ 170,00) foi observada no caminho feliz da execução anterior no AWS Academy.
- **Plano B:** captura previamente sanitizada do mesmo fluxo. Fake/WireMock **não** vale como prova.
- **Estado do estorno (obrigatório na fala):** o pagamento real foi concluído no caminho feliz (R$ 130,00 em 11/11/2026), mas o **estorno falhou** também na execução de 11/11 (HTTP 401 do Mercado Pago sandbox nas 3 tentativas; saga `FALHA_COMPENSACAO`, pagamento `CONFIRMADO` sem estorno). A correção ([billing #23](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/pull/23)) está em `main`; a causa não foi determinada (escopo do token sandbox ou limitação do sandbox). Não declarar estorno concluído: é defeito aberto, registrado na seção 10.

### 8. CI, cobertura, Sonar e New Relic (11:15–12:45)
- **Abrir (web):**
  1. Actions, run de `main` do core: <https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42953101412>; billing: <https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/actions/runs/42953106505>; execution: <https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/actions/runs/42953113662>;
  2. SonarCloud, Quality Gate e cobertura em código novo: <https://sonarcloud.io/project/overview?id=rafaelxvr_Tech-challenge-15SOAT> (core), <https://sonarcloud.io/project/overview?id=rafaelxvr_Tech-challenge-15SOAT-billing> (billing) e <https://sonarcloud.io/project/overview?id=rafaelxvr_Tech-challenge-15SOAT-execution> (execution);
  3. `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · seção 7 "Qualidade e testes (SonarCloud)" (p. 7); `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · seção 6 "Observabilidade (New Relic)" (p. 6);
  4. New Relic (`one.newrelic.com` → **Query your data**), com o `sagaId` impresso por `node bdd.mjs start C2` na pasta `D:\unravel-private\aws-learner-lab\sessao6` (ou pela request "05.5m - Observabilidade: NRQL do sagaId (New Relic)" do Postman); colar, trocando `<sagaId>`:
     ```
     FROM Transaction SELECT count(*) AS transacoes, uniqueCount(traceId) AS tracesDistintos, uniques(appName) AS servicos WHERE sagaId = '<sagaId>' SINCE 3 hours ago
     ```
     e, para um trace por etapa:
     ```
     FROM Transaction SELECT count(*) AS transacoes, uniques(appName) AS servicos WHERE sagaId = '<sagaId>' FACET traceId SINCE 3 hours ago LIMIT MAX
     ```
- **Tela/ação:** runs de CI de `main` de 11/11/2026 (core [42953101412](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42953101412), billing [42953106505](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/actions/runs/42953106505), execution [42953113662](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/actions/runs/42953113662), todos `success`); Sonar Quality Gate aprovado nos três. **Cobertura em código novo, não total** (`new_coverage`, check SonarCloud do SHA de `main`): core 91,1%, billing 92,6%, execution 93,4%; dizer que é cobertura em código novo e que a cobertura total não foi medida. No New Relic: transações dos três serviços e a consulta `FROM Transaction SELECT ... WHERE sagaId = '<id>'`.
- **Prova:** qualidade e observabilidade.
- **Evidência esperada:** Quality Gate aprovado nos três `main`. **Trace:** **um `trace.id` por etapa (4 gatilhos HTTP: criação da OS, ações do mecânico, decisão do cliente, pagamento) + correlação da OS inteira por `sagaId` via NRQL**; **não afirmar trace único da OS inteira**. Provado no Kind local (4 `traceId` por `sagaId` nos heads dos PRs #126/#28/#53; CI do PR #126 verde, [run 42934949615](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42934949615)). No AWS Academy: a informar.
- **Plano B:** mostrar os PRs de instrumentação mesclados e a prova do Kind (4 traces por `sagaId`), dizendo explicitamente que é prova local, que o critério é um trace por etapa mais correlação por `sagaId`, e que a prova no AWS Academy ainda não existe.

### 9. DLQ/redrive e reconciliação (12:45–13:45)
- **Abrir:**
  1. runbook de DLQ ([#93](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/93)): `Tech-challenge-15SOAT` · `docs/runbooks/saga-recuperacao-d19r.md` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/docs/runbooks/saga-recuperacao-d19r.md) · local `D:\repository\fiap-entrega\docs\runbooks\saga-recuperacao-d19r.md`; seções 1 "Detecção", 3.B "Mensagem na DLQ: redrive manual" e 3.D "Saga em `FALHA_COMPENSACAO`: reconciliação";
  2. cenários BDD de DLQ e redrive: `Tech-challenge-15SOAT` · `tests/phase4-kind/features/08-retry-dlq-redrive.feature` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/fase4-entrega-2026-11-12/tests/phase4-kind/features/08-retry-dlq-redrive.feature) · local `D:\repository\fiap-entrega\tests\phase4-kind\features\08-retry-dlq-redrive.feature`;
  3. terminal (Kind, plano B), pasta `D:\repository\fiap-entrega`: `kubectl -n infra exec deploy/localstack -- awslocal sqs get-queue-attributes --queue-url "http://localhost:4566/000000000000/billing-commands-dlq.fifo" --attribute-names ApproximateNumberOfMessages --query 'Attributes.ApproximateNumberOfMessages' --output text` (profundidade da DLQ antes e depois do redrive; comando da seção 1 do runbook).
- **Tela/ação:** mensagem na DLQ, redrive seguro, reconciliação idempotente.
- **Prova:** recuperação operacional.
- **Evidência esperada:** DLQ com profundidade antes/depois do redrive e saga reconciliada sem efeito duplicado.
- **Plano B:** cenário DLQ/redrive da matriz BDD do run 40884415348. Execução no AWS Academy: a informar.

### 10. Limitações e encerramento (13:45–14:30)
- **Abrir:** `Tech-challenge-15SOAT` · `docs/entrega-fase-4.pdf` · [GitHub](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/develop/docs/entrega-fase-4.pdf) · local `D:\repository\fiap-entrega\docs\entrega-fase-4.pdf` · seção 10 "Evidências AWS Academy" (p. 9) e seção 11 "Limitações e riscos conhecidos" (p. 10).
- **Tela/ação:** seções 10 e 11 do documento de entrega.
- **Prova:** transparência sobre o que está e o que não está evidenciado.
- **Fala obrigatória:** deploy de produção do core desabilitado por contrato; no AWS Academy: caminho feliz e trace por etapa atendidos, estorno real com falha (HTTP 401 no sandbox, defeito aberto) e ReparoRecusado não executado (só no Kind), conforme a seção 10; trace por etapa provado no Kind; critério de trace é um por etapa mais `sagaId`. Encerrar sem afirmar o que não tem prova.
- **Plano B:** slide estático com as limitações.

## Verificações pré-gravação

- Rodar `python scripts/check-roteiro-paths.py` na raiz do core: todo path citado neste roteiro existe no repositório e na tag ou branch do link. Abrir uma amostra dos links de verdade.
- Deixar abertas as abas dos blocos 2, 3, 7, 8 e 9 e o terminal em `D:\unravel-private\aws-learner-lab\sessao6`, para não procurar arquivo durante a gravação.
- Conferir a tabela "Evidências AWS Academy" (seção 10 do documento de entrega): a fala segue o resultado registrado, sem declarar atendido o que a tabela marca como não atendido ou não executado.
- Reconferir SHAs de `main`, digests do manifesto e métricas do Sonar, e regenerar o PDF.
- Inventário AWS final zero e `terraform destroy` ao final da gravação.
- Revisar a tela inteira em busca de segredo, PII, ARN ou ID de recurso antes de publicar.
