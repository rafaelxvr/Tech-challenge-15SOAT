# language: pt
@kind @fluxo-feliz
Funcionalidade: Fluxo feliz da OS pela saga nos três serviços
  Contexto:
    Dado o cluster Kind "oficina-phase4" com os-service, billing (gateway-fake) e execution saudáveis
    E SAGA_ENABLED e SAGA_MENSAGERIA_ENABLED verdadeiros somente no perfil Kind
    E uma OS sintética com um serviço e uma peça com estoque suficiente

  @BDD-01a
  Cenário: OS percorre todas as etapas até ser finalizada
    Quando o mecânico conclui o diagnóstico em execution
    E o cliente sintético aprova o orçamento
    E o pagamento é aprovado pela simulação do billing
    E o mecânico conclui o reparo em execution
    Então a saga passa por AGUARDANDO_DIAGNOSTICO, AGUARDANDO_ORCAMENTO, AGUARDANDO_DECISAO, AGUARDANDO_PAGAMENTO, AGUARDANDO_REPARO, EM_EXECUCAO e CONCLUIDA
    E os comandos enviados são IniciarDiagnostico, GerarOrcamento, RegistrarDecisao e IniciarReparo, nessa ordem
    E a OS termina FINALIZADA com o estoque da peça decrementado
