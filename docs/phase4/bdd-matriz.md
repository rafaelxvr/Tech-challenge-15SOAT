# Fase 4 — Matriz BDD e testes de integração para Kind efêmero

Origem: UNR-85. Base: `develop` do os-service `cf34717`, billing `96c811a`, execution `1bf1e6c` (28/10/2026).
Fonte de verdade: o código em `develop`; onde ele difere do Plan 0 ou do v7, a divergência está registrada abaixo.

## O que existe

| Arquivo | Função |
|---|---|
| `tests/phase4-kind/oraculos.json` | Oráculos por cenário: estados da saga, comandos, replies, pilha de compensação, motivo, status final da OS e contagens |
| `tests/phase4-kind/features/*.feature` | 36 cenários em Gherkin pt-BR (`# language: pt`), uma feature por categoria exigida |
| `tests/phase4-kind/fixtures/sinteticos.json` | Dados sintéticos: cliente, OS, peça, filas, mensagens venenosas, rotas e saúde |
| `scripts/phase4-kind-bdd.ps1` | Runner: `Preflight`, `Run`, `Report`; grava `bdd-results.json` ligado aos três SHAs |
| `tests/phase4-bdd-contract.ps1` | Contrato estático (sem cluster): oráculos, features, fixtures e enums do código não podem divergir |

Os envelopes de reply reutilizam `src/test/resources/contracts/fixtures/<Tipo>.json` (já validados contra os schemas v1).

## Matriz

Modo: **3S** = fluxo real pelos três serviços; **INJ-R** = harness injeta replies na fila de respostas; **INJ-C** = harness injeta comandos na fila do serviço; **UNIT** = coberto por teste unitário existente, sem simulação em Kind.

| Categoria | Cenários | Modo |
|---|---|---|
| Fluxo feliz | 01a | 3S |
| Recusa de pagamento/orçamento | 02a pagamento recusado; 02b orçamento recusado (versão 2) | 3S |
| Falha pós-pagamento/estorno | 03a estoque insuficiente; 03b reparo recusado (pilha completa); 03c `CompensacaoFalhou`; 03d pivô | 3S, 03c INJ-R |
| Duplicidade/idempotência | 04a reply duplicada; 04b compensação reenviada; 04c comando duplicado; 04d simulação repetida; 04e reentrega da outbox | INJ-R, INJ-C, 3S |
| Reply tardia | 05a aprovação após cancelamento; 05b `DiagnosticoConcluido` obsoleta; 05c incompatível/sem saga/desconhecida | 3S, INJ-R |
| Scheduler | 06a diagnóstico; 06b decisão expirada; 06c decisão sem resposta; 06d e 06e expiração de pagamento (saga x billing); 06f reparo sem resposta; 06g teto de reenvios; 06h isolamento do sweep | 3S, 06h UNIT |
| Indisponibilidade outbox/LocalStack | 07a decisão com LocalStack fora; 07b replies acumuladas; 07c backoff/`BLOCKED`; 07d compensação esgotada (M7) | 3S, 07c UNIT |
| Retry/DLQ/redrive | 08a venenosa e outro grupo; 08b retenção do grupo; 08c decisão de orçamento desconhecido; 08d reply venenosa; 08e redrive | INJ-C, INJ-R |
| Capacidade de reparos | 09a terceiro recusado; 09b concorrência; 09c liberação; 09d suíte repetida duas vezes | 3S, INJ-C |

## Oráculos de referência (do código)

- Estados (`EstadoSaga`): `AGUARDANDO_DIAGNOSTICO → AGUARDANDO_ORCAMENTO → AGUARDANDO_DECISAO → AGUARDANDO_PAGAMENTO → RESERVANDO_PECAS → AGUARDANDO_REPARO → EM_EXECUCAO → CONCLUIDA`; falhas passam por `COMPENSANDO` e terminam em `CANCELADA` ou `FALHA_COMPENSACAO`. `RESERVANDO_PECAS` é transitório (mesma transação do `PagamentoConfirmado`); a consulta REST só o enxerga nos `passos`.
- Pilha de compensação: diagnóstico empilha `CANCELAR_EXECUCAO`; `DiagnosticoConcluido` troca por `CANCELAR_ORCAMENTO`; pagamento confirmado empilha `ESTORNAR_PAGAMENTO`; peças reservadas empilham `REPOR_ESTOQUE` (local) e `CANCELAR_EXECUCAO`; `ReparoIniciado` esvazia a pilha. A compensação desempilha do topo; o teste estático confere que os comandos enviados são o inverso da pilha.
- Descartes de reply (log `event_name`): `saga_resposta_duplicada`, `saga_resposta_obsoleta`, `saga_resposta_incompativel`, `saga_resposta_sem_saga`, `saga_resposta_desconhecida`.
- Prazos: poller de prazos a cada 15 s e outbox a cada 1 s. Defaults (3d/5m/7d/30m/5m, 5 tentativas) não servem em Kind; o perfil de prazos curtos está em `oraculos.json` (`perfilPrazosCurtos`). Teto: `maxTentativas=3` ⇒ 1 envio + 2 reenvios; com o default 5, 5 envios (risco M7 do D19).
- Billing: `Orcamento` GERADO/APROVADO/RECUSADO/CANCELADO; `Pagamento` PENDENTE/CONFIRMADO/RECUSADO/EXPIRADO/CANCELADO/ESTORNADO; simulação `POST /billing/simulacoes/pagamentos/{id}` com `approved|rejected`, papel ADMIN, só no perfil `gateway-fake`.
- Execution: `StatusExecucao` NA_FILA/EM_ANDAMENTO/CONCLUIDA/CANCELADA/RECUSADA; capacidade por filial via `EXECUCAO_CAPACIDADE_REPAROS` (default 5), ajuste atômico no DynamoDB.

## Divergências registradas (código em `develop` x planos)

1. **Portas.** Plan 0 §8 lista os serviços em 8081/8082. No código, os três `Service` expõem 8080 e cada pod escuta 8080; 8081/8082 só valem como porta local do `kubectl port-forward`.
2. **Cluster do billing.** `scripts/deploy-kind.sh` do billing cria por padrão o cluster `oficina-billing`; o contrato é `oficina-phase4` (`KIND_CLUSTER`). O execution já assume o cluster do UNR-68.
3. **BDD em Cucumber.** Plan 0 §10 prevê Cucumber com `docker-compose.full.yml`. Em `develop` não há dependência Cucumber nem esse compose; o esqueleto atual usa Gherkin como especificação, oráculos em JSON e runner PowerShell, alinhado ao `phase4-kind*.ps1`. Se o Rafael quiser Cucumber/JUnit, os `.feature` migram sem mudança.
4. **Telemetria D8/D19.** Não existem em `develop` `oficina.saga.mensageria.dlqs`, o evento `SagaFilaSaude` nem alerta de `BLOCKED`/DLQ. 07d e 08 verificam estado e filas diretamente; alertas ficam fora até a Task 6 do Plan 4.
5. **Overlay Kind do os-service.** `application-kind.yml` e `k8s/overlays/kind` do os-service ainda não estão em `develop` (UNR-69 em andamento). `Preflight` exige `SAGA_ENABLED` e `SAGA_MENSAGERIA_ENABLED` no deployment `oficina-app`.
6. **Billing.** Spring Boot 3.5.16 (exceção UNR-234), sem impacto nos contratos de mensagem.
7. **Hold-back FIFO (08b) e liberação de vaga (09c).** O hold-back está descrito no Plan 1 e a liberação de capacidade é inferida de `AjusteCapacidade`; ambos são oráculos a confirmar na primeira execução real, não fatos medidos aqui.
8. **`StartMessageMoveTask` (08e).** Suporte no LocalStack 3.8 não verificado; o redrive é manual (receive + send).

## Como rodar

```powershell
pwsh -NoProfile -File tests/phase4-bdd-contract.ps1            # estático, sem cluster
pwsh -NoProfile -File scripts/phase4-kind-bdd.ps1 -Action Preflight
pwsh -NoProfile -File scripts/phase4-kind-bdd.ps1 -Action Run -BillingSha <sha> -ExecutionSha <sha> -OutputDir <dir>
```

`Run` sai com 2 enquanto houver cenário `NAO_EXECUTADO` e com 1 se algum falhar. Nada é marcado como aprovado sem handler.

## Evidência desta entrega

- Executado: `tests/phase4-bdd-contract.ps1` → `OK: 36 cenários, 9 features, 6 filas verificadas`; mutação de um estado no oráculo faz o contrato falhar; `tests/phase4-kind-contract.ps1` sem falhas; parse do runner sem erros.
- **Não executado:** nenhum cenário em Kind (os handlers do runner ainda não existem e o os-service não está implantado no cluster); sem `mvn`, sem cobertura, sem Sonar. Resultados por SHA só existem quando UNR-69 rodar o `Run`.
- Sem credenciais, PII, chamadas AWS ou alteração de infraestrutura remota; o runner só usa o contexto `kind-oficina-phase4`.

## Execução em Kind (UNR-69) — primeira rodada

Runner: handlers em `scripts/phase4-kind-bdd.handlers.ps1`, suporte (port-forward em loopback, JWT sintético de staff HS256 e de cliente RS256 com par gerado em memória, SQL via `Invoke-Psql`) em `scripts/phase4-kind-bdd.support.ps1`. `Run` troca a chave pública de cliente do os-service pela do runner (a privada nunca sai do processo) e reinicia o deployment antes dos cenários.

| Cenário | Status | Observação |
|---|---|---|
| BDD-01a | PASS | estados, comandos, pilha vazia, `FINALIZADA`, `saga_outbox`=4, `saga_inbox`=6, execuções, orçamento, pagamento e estoque conferem. SHAs: os `cf34717`, billing `96c811a`, execution `1bf1e6c` |
| BDD-02a | PASS | recusa de pagamento: `COMPENSANDO` → `CANCELADA`, motivo `PAGAMENTO_RECUSADO`, orçamento `CANCELADO`, pagamento `RECUSADO`, estoque intacto, OS `CANCELADA` |
| BDD-02b | PASS | cliente recusa: saga volta a `AGUARDANDO_DIAGNOSTICO` com `versaoOrcamento` 2, novo `IniciarDiagnostico`, 2 execuções de diagnóstico, orçamento `RECUSADO`, OS `EM_DIAGNOSTICO` |
| BDD-03a | PASS | estoque insuficiente: `EstornarPagamento` e `CancelarOrcamento` nessa ordem, `ESTOQUE_INSUFICIENTE`, pagamento `ESTORNADO`, orçamento `CANCELADO`, estoque intacto. O oráculo descreve só o trecho pós-pagamento: comparado com o sufixo observado |
| BDD-03b | PASS | capacidade esgotada (`EXECUCAO_CAPACIDADE_REPAROS=1` só no cenário, restaurada no `finally`): a OS A ocupa a vaga; a OS B recebe `ReparoRecusado`, passa por `COMPENSANDO` e `CancelarExecucao`, `EstornarPagamento`, `CancelarOrcamento` saem nessa ordem; `CANCELADA`, motivo `REPARO_RECUSADO`, pilha vazia, execução `RECUSADA`, pagamento `ESTORNADO`, orçamento `CANCELADO`, estoque da OS B sem decremento líquido (`REPOR_ESTOQUE` local). A OS A é concluída ao final (libera a vaga). Pré-condição verificada no DynamoDB: 0 vagas ocupadas |
| BDD-03d | PASS | pivô: `ReparoIniciado` leva a saga a `EM_EXECUCAO` com pilha vazia e sem `prazo`; depois de um ciclo inteiro do poller de prazos (20 s) continua `EM_EXECUCAO`, sem passo `COMPENSANDO`, OS `EM_EXECUCAO` |
| BDD-04d | PASS | segunda simulação `approved` (HTTP 200): uma única `PagamentoConfirmado` na outbox do billing, uma linha correspondente no `saga_inbox`, pagamento `CONFIRMADO`=1, um `IniciarReparo` e estoque decrementado uma vez |
| BDD-04e | PASS | com a execution em 0 réplicas, `IniciarDiagnostico` fica na fila; a linha da outbox volta a `PENDING` e é republicada com o mesmo `messageId`: o SQS devolve o mesmo `MessageId` (dedup FIFO), a fila retém 1 mensagem antes e depois, e ao religar a execution há 1 execução `DIAGNOSTICO` |
| BDD-05a | PASS | aprovação após `CANCELADA` (saga cancelada por `SAGA_PRAZO_PAGAMENTO=30s`): pagamento `ESTORNADO`, orçamento `CANCELADO`, zero respostas novas do billing e zero mensagens novas no `saga_inbox`, OS `CANCELADA`. SHAs: os `cf34717`, billing `96c811a`, execution `1bf1e6c`. |
| BDD-06a | PASS | `SAGA_PRAZO_DIAGNOSTICO=30s`: `AGUARDANDO_DIAGNOSTICO` → `COMPENSANDO` → `CANCELADA`, `DIAGNOSTICO_SEM_RESPOSTA`, `CancelarExecucao`, execução de diagnóstico `CANCELADA`. |
| BDD-06b | PASS | `SAGA_PRAZO_DECISAO=30s`: `DECISAO_EXPIRADA`, `CancelarOrcamento`, orçamento `CANCELADO`, OS `CANCELADA`. |
| BDD-06d | PASS | `SAGA_PRAZO_PAGAMENTO=30s`: `PAGAMENTO_EXPIRADO`, pagamento `CANCELADO`; aprovação tardia (HTTP 200) vira `ESTORNADO` e a saga permanece `CANCELADA`. |
| demais 23 | NAO_EXECUTADO ou COBERTO_POR_UNITARIO | handlers ainda não escritos (03c, 04a–04c, 05b–05c dependem de injeção no LocalStack; 06c, 06e–06g, 07–09 em fatias seguintes) |

Fatia 1 (UNR-283): cluster efêmero `oficina-p4-unr283` (nome distinto para não colidir com a rodada do UNR-69), SHAs os `cf34717`, billing `96c811a`, execution `1bf1e6c`, sem egress AWS (LocalStack, credenciais fictícias). Os quatro cenários passaram em cluster limpo; em execuções separadas do mesmo cluster 01a, 03d, 04d (rodada 1) e 03b, 04e (rodada 2). Handlers em `scripts/phase4-kind-bdd.handlers.pos-pagamento.ps1`.

Achados da fatia 1:

- **`AGUARDANDO_REPARO` dura ~0,5 s.** A execution responde `ReparoIniciado` assim que aceita `IniciarReparo` (a execução fica `NA_FILA` e a vaga de capacidade é ocupada nesse momento); o `iniciar` do mecânico não é o gatilho. Esperar por `AGUARDANDO_REPARO` por polling de 1 s é corrida: o ponto estável é `EM_EXECUCAO`. O handler do 01a tinha essa espera e foi corrigido; o `ReparoIniciado` observado em 03d é, portanto, o aceite da execution.
- **A vaga de capacidade vaza se o cenário falha depois de `IniciarReparo`** (item `CAPACIDADE#MATRIZ`, `reparosAtivos`): o 03b falha com pré-condição clara em vez de falso negativo, e a solução é cluster limpo. Cenários que aceitam reparo concluem a execução ao final para devolver a vaga.
- **Latência de ~60 s na primeira mensagem depois de reiniciar um deployment**, vista 2 vezes (resposta `DiagnosticoConcluido` do 01a depois do restart do os-service; `IniciarDiagnostico` do 03b depois do restart da execution). Coincide com o `visibilidadePadraoSegundos` de 60 s; a causa (pod antigo consumindo durante a troca) é hipótese, não medida. Mitigação: esperas do primeiro trecho do fluxo com 120 s.
- `kubectl set env NAME=` com valor vazio (em vez de `NAME-`) deixa a execution em `CrashLoopBackOff`; o helper usa a remoção.


Rodada 2 (SHAs os `cf34717`, billing `96c811a`, execution `1bf1e6c`): 01a, 02a, 02b e 03a passam juntos em cluster novo (`oficina-p4-unr69`) subido por `phase4-kind-stack.ps1` com os grants do UNR-280 (branch #74 mesclado aqui), sem contorno manual. O runner usa o cluster de `PHASE4_KIND_CLUSTER`.

Achados que a rodada expôs (todos com tarefa própria):

- **UNR-279 (os-service):** `POST /api/ordens-servico` com placa não cadastrada devolve 500 (`UnexpectedRollbackException`: `VeiculoService.obterAtivoPorPlaca` marca a transação como rollback-only e `resolverVeiculo` engole a exceção). O harness pré-cadastra o veículo; a rota de placa nova fica sem cobertura até a correção.
- **UNR-280 (infra Kind, corrigido no PR #74; mesclado neste branch para a rodada 2):** `billing_app` sem DML no bring-up (`permission denied for table outbox/pagamento`); o `GerarOrcamento` não é processado. Na rodada, o contorno foi um `GRANT` manual no cluster de ensaio, não versionado.
- O nome do deployment do os-service no preflight era `oficina-app`; o real é `oficina-os-service`. Corrigido.

Rodada 3 (cluster `oficina-p4-unr69b`, SHAs os `cf34717`, billing `96c811a`, execution `1bf1e6c`): 05a, 06a, 06b e 06d passam. Os prazos da saga são encurtados por `kubectl set env` no os-service só durante o cenário e removidos no `finally`; o poller de prazos roda a cada ~15 s. Handlers em `scripts/phase4-kind-bdd.handlers.scheduler.ps1`. Passar vários ids a `-Scenario` exige `pwsh -Command`; com `-File` a lista chega como uma única string e nenhum cenário roda.
