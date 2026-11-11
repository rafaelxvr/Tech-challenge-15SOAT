# Roteiro do vídeo — Fase 4 (alvo 14min30s, limite 15min)

Roteiro para ensaio e gravação. Documento principal: [`entrega-fase-4.md`](entrega-fase-4.md).

**Integrantes:** Rafael Xavier (RM371756) e Silas Furini (RM374087).

## Regras

- Duração-alvo total: **14min30s**, com 30s de margem até o limite de 15min.
- Ambiente da gravação: **AWS Academy**. O Kind aparece apenas como validação local e de CI antes do deploy, nunca como ambiente gravado.
- Campos ⟨preencher após gravação⟩: URL e timecodes reais só existem depois da gravação. Capturas que dependem do AWS Academy ⟨preencher após sessão 6⟩ vão para a seção 10 do documento de entrega.
- Estado das evidências no AWS Academy: o caminho feliz (C1) foi aprovado em execução anterior (saga `CONCLUIDA`, OS `FINALIZADA`, R$ 170,00), com baseline de imagens anterior ao final. O estorno real, o ReparoRecusado e o trace por etapa no AWS Academy ainda precisam ser repetidos nos digests finais. Não afirmar na fala o que não foi provado.
- Plano B de cada bloco: evidência **pré-capturada ligada ao mesmo SHA**, apresentada como tal. Nunca apresentar fixture ou captura antiga como execução ao vivo.
- Não exibir console com tokens, variáveis secretas, payloads integrais, PII, ARN ou IDs de recursos. Credenciais de Mercado Pago e New Relic ficam fora de banda.

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
- **Prova:** identificação da entrega, com os integrantes e RMs.
- **Evidência esperada:** capa com integrantes e os três repositórios.
- **Plano B:** imagem estática da capa do PDF.

### 2. Repositórios, SHAs e arquitetura (00:45–02:15)
- **Tela/ação:** três repositórios no GitHub; tabela de SHAs e digests de `main` (capa; `sourceSha` e digest provam a origem da imagem; a entrega é a tag `fase4-entrega-2026-11-12`); diagrama da seção 2.1; bancos isolados (SQL e NoSQL).
- **Prova:** serviços independentes e bancos isolados.
- **Evidência esperada:** SHAs exibidos coincidem com os da capa no dia da gravação (reconferir antes). Baseline de `main` de 11/11/2026:
  - core `d9b605bd445a2442af09cad9cdbeee4f4542f4f9`, digest `sha256:704d51aa1f75dc3bce6943768de42d1a8021c69c29fe3b324c09cf2896eb1aa3`, [run 42953101412](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42953101412);
  - billing `140dba5dd782f985ce2d6b5e07acb5aed57114b1`, digest `sha256:bb97e1295bc5a33b65839d61f4338c84cdc7f47340aeb59cce0837b6a2368e94`, [run 42953106505](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/actions/runs/42953106505);
  - execution `fe2d8844c6172e3215603d9f97f5da64be22fc3e`, digest `sha256:e3832274dc29b5e32174bdffeec4c2e50bd05d481107ec8770d964e16fb3af18`, [run 42953113662](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/actions/runs/42953113662).
  - A tabela prova SHA, digest e CI; não prova deploy no AWS Academy dos digests finais.
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
- **Plano B:** BDD-01a do [run 40884415348](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/40884415348), pré-capturado (validação local em Kind com SHAs anteriores; dizer isso na fala). Também existe o caminho feliz aprovado em execução anterior no AWS Academy (saga `CONCLUIDA`, OS `FINALIZADA`, R$ 170,00); citar como resultado dessa execução, não como execução nos digests finais. Execução ao vivo nos digests finais: ⟨preencher após gravação⟩.

### 5. EstoqueInsuficiente e compensação (05:45–07:45)
- **Tela/ação:** OS com peça sem saldo; seguir até a reserva de peças.
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
- **Prova:** falha/compensação 2 (pivô da saga).
- **Passos demonstráveis:**
  1. `ReparoRecusado` retornado pelo Execution (motivo `REPARO_RECUSADO`);
  2. transição da saga para compensação;
  3. compensações: repor estoque, estornar pagamento, cancelar orçamento, cancelar OS;
  4. estado final observável: OS `CANCELADA`, estoque reposto, estorno registrado, fila de execução sem item.
- **Evidência esperada:** log de passos da saga e estado do estoque antes/depois.
- **Plano B:** cenário "reparo recusado por capacidade cheia" da matriz BDD do run 40884415348. Execução no AWS Academy: ⟨preencher após gravação⟩.

### 7. Pagamento sandbox e estorno (09:45–11:15)
- **Tela/ação:** Postman com a coleção `postman/Oficina-Fase4-Kind.postman_collection.json` ([PR #85](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/85)) e o environment `aws-academy` (portas 18080/18081/18082; segredos só no Postman local): preferência, pagamento sandbox, reconsulta e **estorno automático do sistema**.
- **Ordem de execução no Postman (detalhe em `docs/postman/README.md`, seção 5):** (1) pastas 00, 01 e 02; (2) **05.5 C2 real**: OS com peça sem estoque, pagamento manual do checkout com a conta compradora (05.5f), reconsulta (05.5g), saga `CANCELADA` e pagamento `ESTORNADO` verificado no billing (05.5k/05.5l), **sem** chamar a 04.9 (ela estorna direto no provedor e serve só de apoio); (3) se houver tempo, a pasta 04 como C1 (pagamento aprovado, sem falha); (4) ao fim de cada fluxo, o passo de observabilidade (05.5m, 04.10, 03.15) imprime o `sagaId` e a NRQL pronta (`WHERE sagaId = '<id>'`, `FACET traceId`) para colar no New Relic; (5) pasta 06. O ensaio com gateway fake (03, 05.1, 05.2) é só prova local no Kind. Os passos 05.5 e 04 são manuais e exigem `executarMercadoPago=true`.
- **`pagamentoId` no AWS Academy:** o billing não tem GET por `osId` e o banco é privado; informe `pagamentoIdS` à mão antes da 05.5d, ou rode o request a request.
- **Prova:** integração Mercado Pago.
- **Evidência esperada:** pagamento confirmado por reconsulta e estorno concluído; sem tokens, `external_reference` truncado, sem payload integral.
- **Preços em reais (V11):** o catálogo exibe valores em reais (ex.: Alinhamento R$ 120,00; Troca de Óleo R$ 80,00), após a `V11__corrigir_precos_semeados_para_reais.sql`. Antes da correção, um checkout de teste cobrou R$ 17.000 onde o esperado era R$ 170 (centavos gravados em colunas de reais pelo seed V2). A cobrança correta (R$ 170,00) foi observada no caminho feliz da execução anterior no AWS Academy.
- **Plano B:** captura previamente sanitizada do mesmo fluxo. Fake/WireMock **não** vale como prova.
- **Estado do estorno (obrigatório na fala):** o pagamento real de R$ 170,00 foi concluído no caminho feliz, mas o **estorno falhou** na execução anterior (0 estornos no provedor). A correção ([billing #23](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/pull/23)) está em `main`; sua causa original não foi demonstrada. Não declarar estorno concluído sem a evidência da seção 10 (⟨preencher após gravação⟩).

### 8. CI, cobertura, Sonar e New Relic (11:15–12:45)
- **Tela/ação:** runs de CI de `main` de 11/11/2026 (core [42953101412](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42953101412), billing [42953106505](https://github.com/rafaelxvr/Tech-challenge-15SOAT-billing/actions/runs/42953106505), execution [42953113662](https://github.com/rafaelxvr/Tech-challenge-15SOAT-execution/actions/runs/42953113662), todos `success`); Sonar Quality Gate aprovado nos três. **Cobertura em código novo, não total** (`new_coverage`, check SonarCloud do SHA de `main`): core 91,1%, billing 92,6%, execution 93,4%; dizer que é cobertura em código novo e que a cobertura total não foi medida. No New Relic: transações dos três serviços e a consulta `FROM Transaction SELECT ... WHERE sagaId = '<id>'`.
- **Prova:** qualidade e observabilidade.
- **Evidência esperada:** Quality Gate aprovado nos três `main`. **Trace:** **um `trace.id` por etapa (4 gatilhos HTTP: criação da OS, ações do mecânico, decisão do cliente, pagamento) + correlação da OS inteira por `sagaId` via NRQL**; **não afirmar trace único da OS inteira**. Provado no Kind local (4 `traceId` por `sagaId` nos heads dos PRs #126/#28/#53; CI do PR #126 verde, [run 42934949615](https://github.com/rafaelxvr/Tech-challenge-15SOAT/actions/runs/42934949615)). No AWS Academy: ⟨preencher após gravação⟩.
- **Plano B:** mostrar os PRs de instrumentação mesclados e a prova do Kind (4 traces por `sagaId`), dizendo explicitamente que é prova local, que o critério é um trace por etapa mais correlação por `sagaId`, e que a prova no AWS Academy ainda não existe.

### 9. DLQ/redrive e reconciliação (12:45–13:45)
- **Tela/ação:** runbook de DLQ ([#93](https://github.com/rafaelxvr/Tech-challenge-15SOAT/pull/93)); mensagem na DLQ, redrive seguro, reconciliação idempotente.
- **Prova:** recuperação operacional.
- **Evidência esperada:** DLQ com profundidade antes/depois do redrive e saga reconciliada sem efeito duplicado.
- **Plano B:** cenário DLQ/redrive da matriz BDD do run 40884415348. Execução no AWS Academy: ⟨preencher após gravação⟩.

### 10. Limitações e encerramento (13:45–14:30)
- **Tela/ação:** seções 10 e 11 do documento de entrega.
- **Prova:** transparência sobre o que está e o que não está evidenciado.
- **Fala obrigatória:** deploy de produção do core desabilitado por contrato; estorno real, ReparoRecusado e trace por etapa no AWS Academy conforme a tabela da seção 10; trace por etapa provado no Kind; critério de trace é um por etapa mais `sagaId`. Encerrar sem afirmar o que não tem prova.
- **Plano B:** slide estático com as limitações.

## Verificações pré-gravação

- Conferir a tabela "Evidências AWS Academy" (seção 10 do documento de entrega): campos vazios continuam como pendentes na fala.
- Reconferir SHAs de `main`, digests do manifesto e métricas do Sonar, e regenerar o PDF.
- Inventário AWS final zero e `terraform destroy` ao final da gravação.
- Revisar a tela inteira em busca de segredo, PII, ARN ou ID de recurso antes de publicar.
