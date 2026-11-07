package com.oficina.saga.domain;

public enum EtapaSaga {
    DIAGNOSTICO(1), ORCAMENTO(2), DECISAO(3), PAGAMENTO(4), RESERVA_PECAS(5), REPARO(6), EXECUCAO(7);

    private final int numero;
    EtapaSaga(int numero) { this.numero = numero; }
    public int numero() { return numero; }
}
