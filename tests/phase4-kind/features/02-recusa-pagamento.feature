# language: pt
@kind @recusa-pagamento
Funcionalidade: Recusa de pagamento e de orçamento
  Contexto:
    Dado o cluster Kind "oficina-phase4" saudável com billing no perfil gateway-fake
    E uma OS sintética com estoque suficiente

  @BDD-02a
  Cenário: Pagamento recusado cancela orçamento e OS
    Dado a saga em AGUARDANDO_PAGAMENTO com pilha de compensação [CANCELAR_ORCAMENTO]
    Quando a simulação do billing recusa o pagamento
    Então a saga registra o motivo PAGAMENTO_RECUSADO e passa por COMPENSANDO
    E o billing recebe CancelarOrcamento e responde OrcamentoCancelado
    E a saga termina CANCELADA e a OS CANCELADA sem baixa de estoque

  @BDD-02b
  Cenário: Cliente recusa o orçamento e a saga volta ao diagnóstico
    Dado a saga em AGUARDANDO_DECISAO
    Quando o cliente sintético recusa o orçamento
    Então o billing responde OrcamentoRecusado
    E a saga volta a AGUARDANDO_DIAGNOSTICO com versaoOrcamento 2
    E um novo IniciarDiagnostico é enviado à execution
