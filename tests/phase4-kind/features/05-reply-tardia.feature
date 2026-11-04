# language: pt
@kind @reply-tardia
Funcionalidade: Replies tardias, obsoletas ou inválidas
  Contexto:
    Dado o cluster Kind "oficina-phase4" saudável

  @BDD-05a
  Cenário: Aprovação de pagamento depois da saga cancelada
    Dado uma saga CANCELADA com pagamento CANCELADO no billing
    Quando a aprovação chega ao billing
    Então o billing estorna automaticamente e não emite reply
    E a saga e a OS continuam CANCELADA

  @BDD-05b
  Cenário: DiagnosticoConcluido depois do prazo
    Dado a saga cancelada por DIAGNOSTICO_SEM_RESPOSTA
    Quando DiagnosticoConcluido chega com a causationId antiga
    Então o log registra saga_resposta_obsoleta e a saga não muda

  @BDD-05c
  Esquema do Cenário: Replies que a saga descarta
    Quando chega uma reply <caso>
    Então o log registra <evento> e a saga não muda

    Exemplos:
      | caso                                         | evento                     |
      | de tipo incompatível com o comando aguardado | saga_resposta_incompativel |
      | de uma saga inexistente                      | saga_resposta_sem_saga     |
      | de um tipo desconhecido                      | saga_resposta_desconhecida |
