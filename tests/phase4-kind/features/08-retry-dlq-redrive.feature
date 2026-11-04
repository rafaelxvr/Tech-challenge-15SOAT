# language: pt
@kind @dlq
Funcionalidade: Retry, DLQ e redrive
  Contexto:
    Dado as filas FIFO e DLQs com maxReceiveCount 5 e visibilidade reduzida para o teste

  @BDD-08a
  Cenário: Mensagem venenosa vai à DLQ e outro grupo segue
    Quando uma mensagem inválida entra em billing-commands.fifo
    Então após 5 recebimentos ela está em billing-commands-dlq.fifo
    E um comando válido de outra OS é processado normalmente

  @BDD-08b
  Cenário: Mensagens do mesmo grupo ficam retidas
    Quando uma mensagem venenosa é seguida por mensagens válidas do mesmo osId
    Então as posteriores só são processadas depois que a venenosa sai do grupo

  @BDD-08c
  Cenário: RegistrarDecisao para orçamento desconhecido
    Quando RegistrarDecisao referencia um orçamento inexistente
    Então não há reply, a mensagem vai à DLQ e a saga falha com DECISAO_SEM_RESPOSTA

  @BDD-08d
  Cenário: Reply venenosa
    Quando uma reply inválida entra em os-saga-replies.fifo
    Então ela vai à os-saga-replies-dlq.fifo e a saga não muda

  @BDD-08e
  Cenário: Redrive manual
    Dado uma mensagem válida na DLQ cuja causa foi corrigida
    Quando o script move a mensagem de volta à fila original
    Então ela é processada uma vez e a DLQ fica vazia
