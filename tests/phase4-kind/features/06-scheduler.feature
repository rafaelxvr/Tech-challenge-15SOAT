# language: pt
@kind @scheduler
Funcionalidade: Prazos e scheduler da saga
  Contexto:
    Dado o cluster Kind "oficina-phase4" com o perfil de prazos curtos de oraculos.json
    E o poller de prazos executa a cada 15 segundos

  @BDD-06a
  Cenário: Diagnóstico sem conclusão
    Quando o prazo de diagnóstico vence sem ação do mecânico
    Então a saga falha com DIAGNOSTICO_SEM_RESPOSTA e a execution cancela a execução

  @BDD-06b
  Cenário: Cliente não decide
    Quando o prazo de decisão vence sem decisão
    Então a saga falha com DECISAO_EXPIRADA e o billing cancela o orçamento

  @BDD-06c
  Cenário: Billing mudo depois da decisão
    Dado o billing parado após o cliente decidir
    Quando o prazo de resposta automática vence
    Então a saga falha com DECISAO_SEM_RESPOSTA

  @BDD-06d
  Cenário: Prazo de pagamento da saga vence primeiro
    Quando o prazo de pagamento da saga vence
    Então a saga falha com PAGAMENTO_EXPIRADO e o pagamento fica CANCELADO no billing

  @BDD-06e
  Cenário: Validade do pagamento no billing vence primeiro
    Dado BILLING_PAGAMENTO_VALIDADE de 30 segundos e prazo de pagamento da saga de 10 minutos
    Quando o billing expira o pagamento
    Então a reply PagamentoExpirado leva a saga a PAGAMENTO_EXPIRADO

  @BDD-06f
  Cenário: Execution mudo depois do pagamento
    Dado a execution parada depois da reserva de peças
    Quando o prazo de resposta automática vence
    Então a saga falha com REPARO_SEM_RESPOSTA e inicia a compensação pela pilha completa

  @BDD-06g
  Cenário: Compensação sem resposta esgota as tentativas
    Dado o billing parado durante uma compensação e SAGA_MAX_TENTATIVAS_COMPENSACAO igual a 3
    Quando o prazo de compensação vence três vezes
    Então o mesmo comando é enviado 3 vezes (1 envio e 2 reenvios) e a saga termina FALHA_COMPENSACAO

  @BDD-06h @unitario
  Cenário: Isolamento de falhas do scheduler
    Então ExpiracaoPrazosTest e PollerPeriodicoTest provam saga_prazo_falhou e poller_falhou sem derrubar o poller
