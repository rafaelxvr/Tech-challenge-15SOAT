# Roteiro do vídeo — Fase 4 (alvo 14min30s, limite 15min)

Roteiro operacional para ensaio e gravação ([UNR-613](/UNR/issues/UNR-613)). Documento principal: [`entrega-fase-4.md`](entrega-fase-4.md).

## Regras

- Duração-alvo total: **14min30s**, com 30s de margem até o limite de 15min.
- `PENDENTE-VIDEO`: URL e timecodes reais só existem após a gravação.
- `PENDENTE-AWS`: capturas que dependem do ambiente AWS Academy. As sessões AWS 3, 4 e 5 já ocorreram: a 3 revelou o defeito de centavos; a 4 foi parcial (C1 não concluído); a 5 teve **C1 aprovado, C2 parcial por falha no estorno, C3 pulado e trace sem propagação pelo SQS**. As correções (estorno/erro do provedor no billing; propagação do trace pelo SQS e trace por etapa com `sagaId` nos três serviços) estão em `main` e o trace por etapa foi provado no Kind local ([UNR-1201](/UNR/issues/UNR-1201), [UNR-1204](/UNR/issues/UNR-1204)), mas **sem prova na AWS**; a sessão AWS 6, com o Rafael, repete C2, C3 e o trace ([UNR-947](/UNR/issues/UNR-947)), sob aprovação do Rafael. Pendências honestas: C2 completo, C3 e trace na AWS. Este roteiro não autoriza nem executa ação AWS.
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
- **Tela/ação:** três repositórios no GitHub; tabela de SHAs e digests de `main` (capa; `sourceSha`/digest provam a origem da imagem, e o overlay de entrega vem da tag `deliveryRef` `fase4-entrega-2026-11-12`, a ser criada pelo operador após a promoção documental; até lá é referência prevista, não verificada; a tag `fase4-entrega-2026-11-11` é histórica e não é a entrega final); diagrama da seção 2.1; bancos isolados (SQL e NoSQL).
- **Prova:** serviços independentes e bancos isolados.
- **Evidência esperada:** SHAs exibidos coincidem com os da capa no dia da gravação (reconferir antes). Valores de `main` do baseline de 11/11/2026 ([UNR-1207](/UNR/issues/UNR-1207); substituem os de 10/11):
  - core `d9b605bd445a2442af09cad9cdbeee4f4542f4f9`, digest `sha256:704d51aa1f75dc3bce6943768de42d1a8021c69c29fe3b324c09cf2896eb1aa3`, [run 42953101412](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42953101412);
  - billing `140dba5dd782f985ce2d6b5e07acb5aed57114b1`, digest `sha256:bb97e1295bc5a33b65839d61f4338c84cdc7f47340aeb59cce0837b6a2368e94`, [run 42953106505](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/actions/runs/42953106505);
  - execution `fe2d8844c6172e3215603d9f97f5da64be22fc3e`, digest `sha256:e3832274dc29b5e32174bdffeec4c2e50bd05d481107ec8770d964e16fb3af18`, [run 42953113662](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/actions/runs/42953113662).
  - A tabela prova SHA, digest e CI; não prova deploy no AWS Academy dos digests novos (`PENDENTE-AWS`). O manifesto e os overlays `aws` já refletem esse baseline de `main` de 11/11 (SHAs `d9b605bd`, `140dba5d`, `fe2d8844`; digests conforme o `release-manifest.json`; `deliveryRef` `refs/tags/fase4-entrega-2026-11-12`; frente SRE, [UNR-1183](/UNR/issues/UNR-1183)); este roteiro não os altera.
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
- **Plano B:** BDD-01a do [run 40884415348](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/40884415348) (pré-capturado, validação local em Kind com SHAs históricos — dizer isso na fala; não é o ambiente do vídeo). Ao vivo no ambiente final: `PENDENTE-AWS` / `PENDENTE-VIDEO`. Evidência real já existente: o C1 da sessão AWS 5 (baseline V11 anterior) foi aprovado (saga `CONCLUIDA`, OS `FINALIZADA`, R$ 170,00); citar como resultado da sessão 5, não como execução nos digests finais.

### 5. EstoqueInsuficiente e compensação (05:45–07:45)
- **Tela/ação:** OS com peça sem saldo; seguir até a reserva de peças.
- **Prova:** falha/compensação 1.
- **Passos demonstráveis:**
  1. evento/motivo de falha `ESTOQUE_INSUFICIENTE` registrado na saga;
  2. transição da saga para compensação;
  3. compensações em ordem inversa: estorno do pagamento, cancelamento do orçamento, cancelamento da OS;
  4. estado final observável: OS `CANCELADA`, orçamento cancelado, estorno registrado, estoque inalterado.
- **Evidência esperada:** log de passos da saga (`/saga`) com os quatro itens acima.
- **Plano B:** cenário correspondente da matriz BDD do run 40884415348, ligado ao seu SHA. `PENDENTE-AWS` para a execução final. **Na sessão AWS 5 o `EstoqueInsuficiente` funcionou, mas o estorno falhou** (`MERCADO_PAGO_ESTORNO_FALHOU`, saga `FALHA_COMPENSACAO`); o passo 3 (estorno) só pode ser mostrado como "funcionando" após a sessão 6 com a correção do billing. Antes disso, dizer que o estorno falhou e foi corrigido em `main`, sem prova na AWS.

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
- **Preços em reais (V11):** o catálogo exibe valores em reais (ex.: Alinhamento R$ 120,00; Troca de Óleo R$ 80,00), após a `V11__corrigir_precos_semeados_para_reais.sql`. Na sessão AWS 3 o checkout cobrou R$ 17.000 onde o esperado era R$ 170 (centavos gravados em colunas de reais pelo seed V2; [UNR-1105](/UNR/issues/UNR-1105), [UNR-1110](/UNR/issues/UNR-1110)). A cobrança correta com a V11 foi observada no C1 da sessão AWS 5 (R$ 170,00); a captura para o vídeo segue `PENDENTE-VIDEO`.
- **Plano B:** captura previamente sanitizada do mesmo fluxo. Fake/WireMock **não** vale como prova. Execução real: Rafael; `PENDENTE-VIDEO`.
- **Estado do estorno (obrigatório na fala):** na sessão AWS 5 o pagamento real de R$ 170,00 foi concluído no C1, mas o **estorno falhou** (0 estornos no provedor). A correção ([billing #23](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/pull/23)) está em `main`, sua causa original não foi demonstrada e será confirmada na sessão 6. Não declarar estorno concluído sem essa evidência.

### 8. CI, cobertura, Sonar e New Relic (11:15–12:45)
- **Tela/ação:** runs de CI de `main` de 11/11/2026 (core [42953101412](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42953101412), billing [42953106505](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/actions/runs/42953106505), execution [42953113662](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/actions/runs/42953113662), todos `success`); Sonar Quality Gate aprovado nos três (UNR-1207). **Cobertura em código novo, não total** (`new_coverage`, UNR-1207, check SonarCloud do SHA de `main`): core 91,1%, billing 92,6%, execution 93,4%; dizer que é cobertura em código novo e que a cobertura total não foi medida. Valores de 10/11 (89,1%, 92,0%, 93,2%) só como histórico, se citados. No New Relic: transações dos três serviços e a consulta `FROM Transaction SELECT ... WHERE sagaId = '<id>'`.
- **Prova:** qualidade e observabilidade.
- **Evidência esperada:** Quality Gate aprovado nos três `main` (UNR-1207; Sonar no ambiente AWS Academy não executado). **Trace (critério decidido pelo Rafael em 10/11):** **um `trace.id` por etapa (4 gatilhos HTTP: criação da OS, ações do mecânico, decisão do cliente, pagamento) + correlação da OS inteira por `sagaId` via NRQL**; **não afirmar trace único da OS inteira**. Na sessão AWS 5 havia spans nos três serviços, mas nenhum `trace.id` atravessou mais de um serviço (sem propagação pelo SQS). A correção está em `main` e foi provada no **Kind local** ([UNR-1201](/UNR/issues/UNR-1201): 4 `traceId` por `sagaId` nos heads dos PRs #126/#28/#53, com ressalva da QA; [UNR-1204](/UNR/issues/UNR-1204): run 42934949615 verde no PR #126). Na AWS ainda não: `PENDENTE-AWS` (sessão 6).
- **Plano B:** mostrar os PRs de wiring mesclados e a prova do Kind (4 traces por `sagaId`), dizendo explicitamente que é prova local, que o critério é um trace por etapa mais correlação por `sagaId`, e que a prova na AWS ainda não existe.

### 9. D19-R, DLQ/redrive, reconciliação (12:45–13:45)
- **Tela/ação:** runbook D19-R ([#93](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/93)); mensagem na DLQ, redrive seguro, reconciliação idempotente.
- **Prova:** recuperação operacional.
- **Evidência esperada:** DLQ com profundidade antes/depois do redrive e saga reconciliada sem efeito duplicado.
- **Plano B:** cenário DLQ/redrive da matriz BDD do run 40884415348. `PENDENTE-AWS` para execução final.

### 10. Pendências, riscos e encerramento (13:45–14:30)
- **Tela/ação:** seção 11 do documento de entrega.
- **Prova:** rastreabilidade da submissão.
- **Fala obrigatória:** aceite do Kind pelos avaliadores não confirmado; deploy de produção do core pulado por contrato; sessão 5 com C1 aprovado e C2 parcial por estorno, C3 pulado, trace sem propagação (correções em `main`; trace por etapa provado só no Kind, sem prova na AWS); pendências honestas: C2 completo, C3 e trace na AWS (sessão 6). Encerrar sem afirmar o que não tem prova.
- **Plano B:** slide estático com a lista de pendências.

## Verificações pré-gravação

- Conferir se a sessão 6 já tem evidência; sem ela, manter o trace na AWS como pendente (a prova do Kind, UNR-1201/UNR-1204, já existe e vale só para o Kind).
- Reconferir SHAs de `main`/`develop`, digests do manifesto e métricas do Sonar, e regenerar o PDF.
- Inventário AWS final zero e `terraform destroy` ao final da gravação: Rafael ([UNR-613](/UNR/issues/UNR-613)).
- Revisar a tela inteira em busca de segredo, PII, ARN ou ID de recurso antes de publicar.
