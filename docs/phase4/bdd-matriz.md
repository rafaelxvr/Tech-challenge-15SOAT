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

Os SHAs devem ser completos (40 hex minúsculos) e são conferidos contra cada deployment (anotação `phase4.oficina/source-sha` no pod template ou tag da imagem igual ao SHA); divergência aborta, e o digest da imagem em execução vai para `bdd-results.json`. `-Scenario` com id desconhecido ou vazio aborta com exit != 0. O preflight exige `SAGA_ENABLED` e `SAGA_MENSAGERIA_ENABLED` com valor efetivo `true`.

`Run` sai com 2 enquanto houver cenário `NAO_EXECUTADO` e com 1 se algum falhar. Nada é marcado como aprovado sem handler.

## Evidência desta entrega

- Executado: `tests/phase4-bdd-contract.ps1` → `OK: 36 cenários, 9 features, 6 filas verificadas`; mutação de um estado no oráculo faz o contrato falhar; `tests/phase4-kind-contract.ps1` sem falhas; parse do runner sem erros.
- **Não executado:** nenhum cenário em Kind (os handlers do runner ainda não existem e o os-service não está implantado no cluster); sem `mvn`, sem cobertura, sem Sonar. Resultados por SHA só existem quando UNR-69 rodar o `Run`.
- Sem credenciais, PII, chamadas AWS ou alteração de infraestrutura remota; o runner só usa o contexto `kind-oficina-phase4`.
