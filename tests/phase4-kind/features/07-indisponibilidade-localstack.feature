# language: pt
@kind @indisponibilidade
Funcionalidade: Indisponibilidade da outbox e do LocalStack
  Contexto:
    Dado o cluster Kind "oficina-phase4" saudável

  @BDD-07a
  Cenário: LocalStack fora durante a decisão do cliente
    Dado o LocalStack com zero réplicas
    Quando o cliente sintético aprova o orçamento
    Então a decisão é confirmada com a saga em AGUARDANDO_DECISAO
    E o comando fica PENDING na saga_outbox com tentativas crescentes
    Quando o LocalStack volta
    Então RegistrarDecisao é entregue uma única vez e a saga avança

  @BDD-07b
  Cenário: Billing e execution acumulam replies
    Dado o LocalStack com zero réplicas enquanto serviços respondem
    Quando o LocalStack volta
    Então as replies chegam em ordem por OS e sem duplicatas

  @BDD-07c @unitario @lento
  Cenário: Backoff e BLOCKED
    Então BackoffPublicacaoTest e os testes de publisher provam 5 s a 300 s e BLOCKED na 12ª tentativa

  @BDD-07d
  Cenário: Queda maior que prazo x tentativas durante compensação
    Dado uma compensação em andamento e o LocalStack fora por mais que prazo x tentativas
    Então a saga termina FALHA_COMPENSACAO com pilha preservada para reconciliação manual
