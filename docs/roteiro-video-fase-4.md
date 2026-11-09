# Roteiro do vídeo — Fase 4 (alvo 14min30s, limite 15min)

Roteiro operacional para ensaio e gravação ([UNR-613](/UNR/issues/UNR-613)). Documento principal: [`entrega-fase-4.md`](entrega-fase-4.md).

## Regras

- Duração-alvo total: **14min30s**, com 30s de margem até o limite de 15min.
- `PENDENTE-VIDEO`: URL e timecodes reais só existem após a gravação.
- `PENDENTE-AWS`: capturas que dependem do ambiente AWS Academy (nova sessão AWS pós-V11, sessão AWS 4, [UNR-947](/UNR/issues/UNR-947); a sessão AWS 3 já ocorreu e revelou o defeito de centavos), sob aprovação do Rafael. Este roteiro não autoriza nem executa ação AWS.
- Plano B de cada bloco: evidência **pré-capturada ligada ao mesmo SHA**, apresentada como tal. Nunca apresentar fixture ou captura antiga como execução ao vivo.
- Não exibir console com tokens, variáveis secretas, payloads integrais, PII, ARN ou IDs de recursos. Credenciais de Mercado Pago e New Relic ficam fora de banda.
- Risco aberto: o aceite do Kind como evidência de deploy Kubernetes pelos avaliadores **não está confirmado**; **ambiente do vídeo decidido pelo Rafael em 03/11** ([UNR-613](/UNR/issues/UNR-613), [UNR-71](/UNR/issues/UNR-71)): gravação somente no AWS Academy. Kind aparece apenas como validação local antes do deploy, nunca como ambiente gravado.

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
| 9 | 12:45–13:45 | 1:00 | D19-R, DLQ/redrive, reconciliação |
| 10 | 13:45–14:30 | 0:45 | Pendências, riscos e encerramento |

Soma: 0:45 + 1:30 + 1:30 + 2:00 + 2:00 + 2:00 + 1:30 + 1:30 + 1:00 + 0:45 = **14:30**.

## Blocos

### 1. Abertura e escopo (00:00–00:45)
- **Tela/ação:** capa do documento de entrega; fala de abertura.
- **Prova:** identificação da entrega. Participantes: **[CONFIRMAR]** nomes e RM (placeholders até o Rafael fornecer).
- **Evidência esperada:** capa com os três repositórios.
- **Plano B:** imagem estática da capa do PDF.

### 2. Repositórios, SHAs e arquitetura (00:45–02:15)
- **Tela/ação:** três repositórios no GitHub; tabela de SHAs e digests de `main` (capa; mesmos valores do `release-manifest.json`; `sourceSha`/digest provam a origem da imagem, e o overlay de entrega vem da tag `deliveryRef` `fase4-entrega-2026-11-09`, a ser criada pelo operador após a última promoção); diagrama da seção 2.1; bancos isolados (SQL e NoSQL).
- **Prova:** serviços independentes e bancos isolados.
- **Evidência esperada:** SHAs exibidos coincidem com os da capa no dia da gravação (reconferir antes). Valores de 09/11/2026 (core) e 08/11/2026 (billing e execution):
  - core `13cd27dce67784d3e99de669ea6de142fcf5152a`, digest `sha256:aa35819039d1b4a3c63ac8be557e2f15b99dae807dc4b58a145d36236d034804`, [run 42697251364](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42697251364);
  - billing `e4e31147c34e48795a31021dd7265908f7539070`, digest `sha256:0d7ef41cdd4f97a7dafdb21396da3365bd594de26bb9c76b9a4fb51cb33afe6c`, [run 42510613712](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/actions/runs/42510613712);
  - execution `eaac4225fd85799a730653f794cc9407c8677d24`, digest `sha256:203d108eb96e4c9146ef733405a1d093efcbccaef4ba1d5d50ab9601cc11c358`, [run 42508903210](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/actions/runs/42508903210).
  - O manifesto prova SHA, digest e CI; não prova deploy no AWS Academy (`PENDENTE-AWS`).
- **Plano B:** captura da tabela de SHAs e digests com data e hora.

### 3. Contratos, filas, inbox/outbox e Saga (02:15–03:45)
- **Tela/ação:** JSON Schemas de `contracts/`, filas FIFO e DLQs, ADR 006, tabela de compensações (seção 3.3).
- **Prova:** mensageria e orquestração.
- **Evidência esperada:** envelope com `sagaId`, `causationId`, `traceparent`; sem payload real.
- **Plano B:** páginas do documento de entrega.

### 4. Caminho feliz (03:45–05:45)
- **Tela/ação:** abrir OS, diagnóstico, orçamento, aprovação, pagamento, reserva, reparo, finalização; consultar `GET /api/ordens-servico/{id}/saga` a cada etapa.
- **Prova:** conclusão da OS (`FINALIZADA`).
- **Evidência esperada:** saga encerrada com sucesso; estados na ordem da seção 3.2.
- **Plano B:** BDD-01a do [run 40884415348](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/40884415348) (pré-capturado, validação local em Kind com SHAs históricos — dizer isso na fala; não é o ambiente do vídeo). Ao vivo no ambiente final: `PENDENTE-AWS` / `PENDENTE-VIDEO`.

### 5. EstoqueInsuficiente e compensação (05:45–07:45)
- **Tela/ação:** OS com peça sem saldo; seguir até a reserva de peças.
- **Prova:** falha/compensação 1.
- **Passos demonstráveis:**
  1. evento/motivo de falha `ESTOQUE_INSUFICIENTE` registrado na saga;
  2. transição da saga para compensação;
  3. compensações em ordem inversa: estorno do pagamento, cancelamento do orçamento, cancelamento da OS;
  4. estado final observável: OS `CANCELADA`, orçamento cancelado, estorno registrado, estoque inalterado.
- **Evidência esperada:** log de passos da saga (`/saga`) com os quatro itens acima.
- **Plano B:** cenário correspondente da matriz BDD do run 40884415348, ligado ao seu SHA. `PENDENTE-AWS` para a execução final.

### 6. ReparoRecusado e compensação (07:45–09:45)
- **Tela/ação:** forçar capacidade cheia/filial inválida no Execution e iniciar o reparo.
- **Prova:** falha/compensação 2 (pivô da saga).
- **Passos demonstráveis:**
  1. `ReparoRecusado` retornado pelo Execution (motivo `REPARO_RECUSADO`);
  2. transição da saga para compensação;
  3. compensações: repor estoque, estornar pagamento, cancelar orçamento, cancelar OS;
  4. estado final observável: OS `CANCELADA`, estoque reposto, estorno registrado, fila de execução sem item.
- **Evidência esperada:** log de passos da saga e estado do estoque antes/depois.
- **Plano B:** cenário "reparo recusado por capacidade cheia" da matriz BDD do run 40884415348 (revisado em UNR-338/339, aprovado em UNR-69). `PENDENTE-AWS` para a execução final.

### 7. Pagamento sandbox e estorno (09:45–11:15)
- **Tela/ação:** Postman com a coleção ([PR #85](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/85)): preferência, pagamento sandbox, reconsulta, estorno.
- **Prova:** integração Mercado Pago.
- **Evidência esperada:** pagamento confirmado por reconsulta e estorno concluído; sem tokens, `external_reference` truncado, sem payload integral.
- **Preços em reais (V11):** o catálogo exibe valores em reais (ex.: Alinhamento R$ 120,00; Troca de Óleo R$ 80,00), após a `V11__corrigir_precos_semeados_para_reais.sql`. Na sessão AWS 3 o checkout cobrou R$ 17.000 onde o esperado era R$ 170 (centavos gravados em colunas de reais pelo seed V2; [UNR-1105](/UNR/issues/UNR-1105), [UNR-1110](/UNR/issues/UNR-1110)). A cobrança correta com a V11 aplicada ainda não foi reexecutada (`PENDENTE-AWS`); não declarar como evidenciada antes da nova sessão.
- **Plano B:** captura previamente sanitizada do mesmo fluxo. Fake/WireMock **não** vale como prova. Execução real: Rafael; `PENDENTE-VIDEO`.

### 8. CI, cobertura, Sonar e New Relic (11:15–12:45)
- **Tela/ação:** runs de CI de `main` (core [42697251364](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42697251364), billing [42510613712](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/actions/runs/42510613712), execution [42508903210](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/actions/runs/42508903210), todos `success`); Quality Gate e cobertura Sonar por serviço em `main` (consulta pública de 09/11/2026 para o core e 08/11/2026 para billing e execution): core 88,0% (`13cd27d`), billing 91,9% (`e4e3114`), execution 93,6% (`eaac422`); trace distribuído dos três serviços no New Relic.
- **Prova:** qualidade e observabilidade.
- **Evidência esperada:** Quality Gate OK e cobertura ≥ 80% por serviço (medição em `main`, por SHA; Sonar no ambiente AWS Academy não executado); trace único atravessando OS, Billing e Execution. O link APM ainda **não existe**: `PENDENTE-AWS`.
- **Plano B:** mostrar os PRs de wiring mesclados e dizer explicitamente que o trace consultável não foi obtido.

### 9. D19-R, DLQ/redrive, reconciliação (12:45–13:45)
- **Tela/ação:** runbook D19-R ([#93](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/93)); mensagem na DLQ, redrive seguro, reconciliação idempotente.
- **Prova:** recuperação operacional.
- **Evidência esperada:** DLQ com profundidade antes/depois do redrive e saga reconciliada sem efeito duplicado.
- **Plano B:** cenário DLQ/redrive da matriz BDD do run 40884415348. `PENDENTE-AWS` para execução final.

### 10. Pendências, riscos e encerramento (13:45–14:30)
- **Tela/ação:** seção 11 do documento de entrega.
- **Prova:** rastreabilidade da submissão.
- **Fala obrigatória:** aceite do Kind pelos avaliadores não confirmado; deploy de produção do core pulado por contrato; itens ainda pendentes. Encerrar sem afirmar o que não tem prova.
- **Plano B:** slide estático com a lista de pendências.

## Verificações pré-gravação

- Reconferir SHAs de `main`/`develop`, digests do manifesto e métricas do Sonar, e regenerar o PDF.
- Inventário AWS final zero e `terraform destroy` ao final da gravação: Rafael ([UNR-613](/UNR/issues/UNR-613)).
- Revisar a tela inteira em busca de segredo, PII, ARN ou ID de recurso antes de publicar.
