# language: pt
@kind @capacidade
Funcionalidade: Capacidade de reparos da execution
  Contexto:
    Dado EXECUCAO_CAPACIDADE_REPAROS igual a 2 e a filial MATRIZ

  @BDD-09a
  Cenário: Terceiro reparo é recusado
    Quando três OS sintéticas chegam a IniciarReparo
    Então duas recebem ReparoIniciado e a terceira ReparoRecusado com CAPACIDADE_ESGOTADA
    E existem 2 execuções NA_FILA e 1 RECUSADA

  @BDD-09b
  Cenário: Concorrência não excede a capacidade
    Quando seis IniciarReparo de OS distintas chegam em paralelo
    Então exatamente 2 são aceitos e 4 recusados

  @BDD-09c
  Cenário: Concluir libera vaga
    Dado a capacidade esgotada
    Quando um reparo ativo é concluído
    Então o próximo IniciarReparo é aceito

  @BDD-09d
  Cenário: Suíte repetida não vaza capacidade
    Quando a suíte completa roda duas vezes seguidas
    Então os resultados são iguais e não restam execuções ativas após a limpeza
