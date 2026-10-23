package com.oficina.saga.domain;

public enum Compensacao {
    CANCELAR_EXECUCAO(Comando.CANCELAR_EXECUCAO),
    CANCELAR_ORCAMENTO(Comando.CANCELAR_ORCAMENTO),
    ESTORNAR_PAGAMENTO(Comando.ESTORNAR_PAGAMENTO),
    /** Local to os-service: runs in the same transaction, no message. */
    REPOR_ESTOQUE(null);

    private final Comando comando;
    Compensacao(Comando comando) { this.comando = comando; }
    public Comando comando() { return comando; }
    public boolean local() { return comando == null; }
}
