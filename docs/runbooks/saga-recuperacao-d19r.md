# Runbook D19-R: recuperação, redrive e reconciliação da saga

Escopo: os três serviços da Fase 4 (os-service, billing, execution) falando por SQS FIFO. Este runbook cobre quatro
situações e foi **exercitado do zero em Kind efêmero** (seção [Evidência](#evidência)). Nenhum passo exige segredo no
texto: credenciais ficam nos `Secret` do cluster (`data/postgres-os-admin`, `data/postgres-billing-admin`) e são lidas
pelo próprio `kubectl`/script, nunca coladas em issue, PR ou log.

| # | Situação | Cenário BDD |
|---|---|---|
| A | Linha da `saga_outbox` em `BLOCKED` | BDD-07e |
| B | Mensagem na DLQ (comando ou reply) | BDD-08a, 08c, 08d, 08e, 08f |
| C | LocalStack/SQS indisponível | BDD-07a, 07d, 07f |
| D | Saga em `FALHA_COMPENSACAO` | BDD-07d, 07f |

Princípio comum: **a saga e as caixas (outbox/inbox) são a fonte da verdade; a fila é transporte.** Toda recuperação
reentrega o *mesmo* `message_id`; o consumidor descarta duplicata pelo `inbox`. Por isso recuperar é seguro de repetir,
desde que se siga o procedimento abaixo (re-arme só de linha `BLOCKED`, redrive só de mensagem que ainda está na DLQ).

## 0. Pré-requisitos e acesso (Kind)

- Cluster Kind com os três serviços prontos (`scripts/phase4-kind-stack.ps1 -Action Up`), contexto `kind-<cluster>`.
- SQL no os-service: `Invoke-Psql -Instance os -Database oficina -Sql '<consulta>'` (de `scripts/phase4-kind.common.ps1`)
  ou `kubectl -n data exec -i deploy/postgres-os -- psql -d oficina`. No billing: `-Instance billing -Database billing`.
- Filas: `kubectl -n infra exec deploy/localstack -- awslocal sqs ...` (filas `billing-commands.fifo`,
  `execution-commands.fifo`, `os-saga-replies.fifo` e suas `*-dlq.fifo`, `maxReceiveCount=5`).
- Em AWS Academy os mesmos SQL rodam no RDS e as operações de fila no SQS (`aws sqs ...`, perfil `fiap-lab`); **o
  procedimento não foi exercitado em AWS**, só em Kind.

## 1. Detecção

Não há alerta automatizado para `BLOCKED`/DLQ (divergência 4 em `docs/phase4/bdd-matriz.md`). A detecção é por consulta,
a cada verificação operacional e **sempre** antes de declarar uma OS recuperada:

```sql
-- os-service: linhas que o publisher abandonou ou ainda tenta
select os_id, tipo, estado, tentativas, ultimo_erro_codigo, disponivel_em
from saga_outbox where estado in ('BLOCKED','PENDING') and criado_em < now() - interval '2 minutes'
order by sequencia;

-- os-service: sagas que exigem ação humana
select os_id, estado from os_saga where estado = 'FALHA_COMPENSACAO';
```

```sh
# profundidade das DLQs (visíveis + em voo); qualquer valor > 0 é um incidente
for q in billing-commands-dlq.fifo execution-commands-dlq.fifo os-saga-replies-dlq.fifo; do
  kubectl -n infra exec deploy/localstack -- awslocal sqs get-queue-attributes \
    --queue-url "http://localhost:4566/000000000000/$q" --attribute-names ApproximateNumberOfMessages \
    --query 'Attributes.ApproximateNumberOfMessages' --output text
done
```

Sinais nos logs (`kubectl -n <ns> logs deploy/<serviço>`): `billing_comando_falhou` e `saga_resposta_falhou` (um por
recebimento com falha; 5 levam à DLQ), `poller_falhou`, `saga_outbox_publish_failed`.

## 2. Diagnóstico (decidir o procedimento)

1. **Há `BLOCKED` na `saga_outbox`?** Sim → causa foi indisponibilidade prolongada do destino (12 tentativas, backoff
   5 s a 300 s). Confirme que a fila/LocalStack está de volta (passo C.1) e vá para **A**.
2. **Há mensagem na DLQ?** Leia sem consumir (visibilidade 0), identifique o tipo e a causa:
   ```sh
   kubectl -n infra exec deploy/localstack -- awslocal sqs receive-message \
     --queue-url "http://localhost:4566/000000000000/<fila>-dlq.fifo" --max-number-of-messages 10 \
     --visibility-timeout 0 --attribute-names MessageGroupId --output json
   ```
   - Corpo inválido (JSON quebrado, schema): **não redrive**; a mensagem é lixo. Registre o `messageId`/tipo e descarte
     (passo B.4). Redrive de corpo inválido volta à DLQ após 5 recebimentos.
   - Corpo válido, causa externa já corrigida (ex.: dado ausente no destino): vá para **B**.
   - `RegistrarDecisao` para orçamento inexistente: a saga **já falhou sozinha** com `DECISAO_SEM_RESPOSTA`
     (compensa e fecha em `CANCELADA`); a DLQ é só o rastro. Não redrive; descarte.
3. **LocalStack/SQS fora?** → **C**.
4. **Saga em `FALHA_COMPENSACAO`?** → **D**.

## 3. Procedimentos

### A. Outbox `BLOCKED`: re-arme

O publisher só consome `PENDING`; uma linha `BLOCKED` **não volta sozinha**, nem com a fila saudável (medido em
BDD-07e: continuou `BLOCKED` 25 s após o retorno). Mais: enquanto houver linha `BLOCKED` de uma OS, as posteriores
dessa OS ficam retidas (ordem por OS). Passos:

1. Confirmar a causa resolvida (C.1) e anotar `message_id`, `tipo`, `os_id` da linha.
2. Re-armar **uma** linha, idempotente (só age se ainda estiver `BLOCKED`; devolve quantas linhas mudou):
   ```sql
   with r as (
     update saga_outbox
        set estado='PENDING', tentativas=0, disponivel_em=now(), ultimo_erro_codigo=NULL
      where message_id='<MESSAGE_ID>' and estado='BLOCKED'
      returning 1)
   select count(*) from r;   -- 1 = re-armada; 0 = nada a fazer (já PENDING/PUBLISHED)
   ```
   Re-armar não cria mensagem nova: o `message_id` é o mesmo, então a entrega é deduplicada pelo `inbox` do destino.
3. Verificar: a linha vai a `PUBLISHED` (`queue_message_id` preenchido), a saga avança, `BLOCKED` volta a 0 na
   consulta de detecção. Repetir o passo 2 afeta 0 linhas.

Limite: re-arme em lote (`where estado='BLOCKED'` sem `message_id`) só é aceitável após revisar a consulta de detecção
linha a linha; prefira uma linha por vez.

### B. Mensagem na DLQ: redrive manual

`StartMessageMoveTask` **não foi verificado** no LocalStack 3.8: o redrive é manual (receive → send → delete).

1. Corrigir a causa (dado/estado no destino) **antes** de mover.
2. Ler a mensagem da DLQ com visibilidade alta (30 s) guardando corpo, `MessageGroupId` e *receipt handle*.
3. Enviar o **mesmo corpo** à fila original, **mesmo `MessageGroupId`** (o osId) e `MessageDeduplicationId` **novo**
   (o antigo ainda está na janela de 5 min do FIFO e a mensagem seria engolida).
4. Só então `delete-message` na DLQ com o *receipt handle*. Para descartar lixo, pule 3 e apenas apague.
5. Verificar: efeito de negócio uma vez (ex.: um `OrcamentoAprovado`, um pagamento), uma linha no `inbox`
   do consumidor, DLQ e fila vazias.

Idempotência medida (BDD-08f): enviar a mesma mensagem de novo (operador repetiu o passo 3) é descartado pelo `inbox`;
o efeito continua único. Ainda assim, **não repita por rotina**: verifique a DLQ antes.

Ordem: mensagens do mesmo grupo atrás de uma venenosa ficam retidas até ela sair da fila (BDD-08b). Se a venenosa for
lixo, apague-a da DLQ/fila e as demais fluem; se for válida, redrive preserva a ordem do grupo apenas se nada posterior
do mesmo grupo já foi processado, caso contrário confira o estado da saga antes.

### C. LocalStack/SQS indisponível

1. Confirmar: `kubectl -n infra get deploy localstack` (réplicas 0/1) e erros `QUEUE_SEND_FAILED` em
   `saga_outbox.ultimo_erro_codigo`.
2. Restaurar o serviço (`kubectl -n infra scale deploy/localstack --replicas=1` e aguardar o rollout). **Kind:** o
   LocalStack não persiste: rodar `infra/phase4-kind/localstack/init.sh` (idempotente) recria filas e tabelas; mensagens
   em voo e o item de capacidade se perdem, as outboxes (Postgres) não.
3. Esperar a recuperação automática enquanto as linhas estiverem `PENDING` (BDD-07a: `RegistrarDecisao` entregue uma
   vez). Linhas que chegaram a `BLOCKED` → procedimento **A**. Sagas que estouraram prazo na queda → **D**.

### D. Saga em `FALHA_COMPENSACAO`: reconciliação

`FALHA_COMPENSACAO` é terminal no código (`EstadoSaga.terminal()`) e **não há endpoint nem transição de saída**: o runbook reconcilia o efeito remoto, não
o estado da saga.

1. Ler a pilha: `GET /api/ordens-servico/{id}/saga` (`compensacoesPendentes`) ou `os_saga`.
2. Com a fila restaurada, a `saga_outbox` entrega sozinha as compensações `PENDING` (BDD-07f: 3 linhas `CancelarOrcamento`,
   1 envio + 2 reenvios, todas entregues; o billing, idempotente, foi a `CANCELADO` uma vez). Linhas `BLOCKED` → **A**.
3. Para cada item da pilha confirmar no serviço dono o efeito real (orçamento `CANCELADO` no billing; pagamento estornado;
   execução cancelada na execution) e que `saga_outbox` não tem `PENDING`/`BLOCKED` para a OS.
4. Registrar a decisão (OS, pilha, evidência). A saga permanece `FALHA_COMPENSACAO` por desenho: ela é o registro da
   falha, não pendência executável.

## 4. Critérios de parada (não continue; escale ao TechLead)

- O re-arme voltou a `BLOCKED` com `ultimo_erro_codigo` diferente de `QUEUE_SEND_FAILED`: causa não é fila; investigue
  antes de re-armar de novo (não faça laço).
- Mensagem redriven voltou à DLQ: a causa não foi corrigida. Pare, não reenvie.
- Contagem de efeitos de negócio > 1 para o mesmo `message_id` (inbox violado), ou linhas no `inbox` sem efeito:
  **incidente de integridade**, congele a OS e escale.
- Pilha de `FALHA_COMPENSACAO` com item cujo efeito remoto não converge após a fila saudável: estorno/cancelamento manual
  fora deste runbook exige decisão do TechLead.
- Qualquer dado real de cliente em evidência: pare e remova (somente dados sintéticos).

## 5. Rollback

- **Re-arme (A):** a linha só mudou de `BLOCKED` para `PENDING`. Para desfazer antes de publicar, repita
  `update saga_outbox set estado='BLOCKED' where message_id='<MESSAGE_ID>' and estado='PENDING'`. Depois de `PUBLISHED`
  não há desfazer: o comando já foi entregue (e é idempotente no destino).
- **Redrive (B):** antes do passo 4, nada mudou (a mensagem segue na DLQ). Depois do envio, o efeito de negócio só se
  desfaz por compensação da saga, não por este runbook.
- **Fila/LocalStack (C):** `scale --replicas=0` reverte a restauração; as caixas no Postgres não são alteradas.
- Visibilidade de fila alterada para teste (60 s → 5 s) volta ao padrão (`set-queue-attributes VisibilityTimeout=60`).

## Evidência

Execução do exercício (UNR-620), cluster efêmero `oficina-p4-unr620`, sem AWS, imagens locais, dados sintéticos:

| Serviço | SHA |
|---|---|
| os-service | `6c5224db2689b6cbd00fa041ec78cfd933a12dd0` (`develop`) |
| billing | `17a37b9ad8d7e859c637b383fca265f8f734d6ca` (`develop`) |
| execution | `0669c4bfd1170e75c5726621aee51fcf329a3704` (`develop`) |

Resultado: BDD-07a, 07d, 07e, 07f, 08a, 08b, 08c, 08d, 08e, 08f **PASS**; BDD-07c coberto por unitário. Detalhe por
cenário em `docs/phase4/bdd-matriz.md` (Fatia 7). Cleanup: `phase4-kind-stack.ps1 -Action Down` removeu o cluster
(`containers=0 port-forwards=0 images-removed=3`).

Limites do exercício: (1) o `BLOCKED` foi alcançado com o fixture `tentativas=11` (SQL sintético) porque 12 tentativas só
com o backoff real levam mais de 20 min; a transição para `BLOCKED` é feita pelo publisher real; (2) só a outbox do
os-service foi levada a `BLOCKED`; as outboxes do billing (Postgres) e da execution (DynamoDB) usam o mesmo contrato
mas **não** foram bloqueadas aqui; (3) sem alerta automatizado; (4) `StartMessageMoveTask` não verificado; (5) nada
exercitado em AWS.
