# language: pt
@kind @idempotencia
Funcionalidade: Duplicidade e idempotência
  Contexto:
    Dado o cluster Kind "oficina-phase4" saudável

  @BDD-04a
  Cenário: Reply duplicada com o mesmo messageId
    Dado uma saga aguardando DiagnosticoConcluido
    Quando a mesma reply é entregue duas vezes
    Então a saga transita uma única vez e o log registra saga_resposta_duplicada

  @BDD-04b
  Cenário: Compensação reenviada com novo messageId
    Dado um orçamento já cancelado pelo billing
    Quando CancelarOrcamento chega de novo com outro messageId
    Então o billing responde OrcamentoCancelado com o mesmo orcamentoId e não repete o efeito

  @BDD-04c
  Cenário: Comando duplicado na execution
    Quando IniciarDiagnostico chega duas vezes com o mesmo messageId
    Então existe uma única execução DIAGNOSTICO para a OS

  @BDD-04d
  Cenário: Simulação de pagamento aprovado repetida
    Dado a saga em AGUARDANDO_PAGAMENTO
    Quando a simulação "approved" é chamada duas vezes
    Então há uma única PagamentoConfirmado na fila de respostas

  @BDD-04e
  Cenário: Reentrega at-least-once da outbox
    Quando a publicação é repetida para o mesmo messageId
    Então a fila FIFO retém uma única mensagem por messageId
