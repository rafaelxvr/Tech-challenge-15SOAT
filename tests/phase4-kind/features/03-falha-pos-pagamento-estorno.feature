# language: pt
@kind @estorno
Funcionalidade: Falha depois do pagamento e estorno
  Contexto:
    Dado o cluster Kind "oficina-phase4" saudável e uma OS sintética com pagamento confirmado

  @BDD-03a
  Cenário: Estoque insuficiente estorna o pagamento
    Dado uma peça com estoque menor que a quantidade da OS
    Quando o billing responde PagamentoConfirmado
    Então a saga registra ESTOQUE_INSUFICIENTE e passa por COMPENSANDO com pilha [CANCELAR_ORCAMENTO, ESTORNAR_PAGAMENTO]
    E os comandos EstornarPagamento e CancelarOrcamento são enviados nessa ordem
    E a saga termina CANCELADA, o pagamento ESTORNADO e o orçamento CANCELADO

  @BDD-03b
  Cenário: Reparo recusado por capacidade compensa toda a pilha
    Dado a capacidade de reparos da execution esgotada
    Quando a execution responde ReparoRecusado com CAPACIDADE_ESGOTADA
    Então os comandos CancelarExecucao, EstornarPagamento e CancelarOrcamento são enviados nessa ordem
    E REPOR_ESTOQUE roda localmente sem mensagem e o estoque volta ao valor inicial
    E a saga termina CANCELADA

  @BDD-03c
  Cenário: Compensação que falha termina em FALHA_COMPENSACAO
    Dado a saga em COMPENSANDO aguardando EstornarPagamento
    Quando o billing responde CompensacaoFalhou
    Então a saga termina FALHA_COMPENSACAO com a pilha [CANCELAR_ORCAMENTO, ESTORNAR_PAGAMENTO] preservada
    E a OS não é cancelada

  @BDD-03d
  Cenário: Depois de ReparoIniciado nada é compensado
    Quando a execution responde ReparoIniciado
    Então a saga fica EM_EXECUCAO com pilha vazia e sem prazo
