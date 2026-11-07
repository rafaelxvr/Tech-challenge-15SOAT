package com.oficina.saga.domain;

public enum Comando {
    INICIAR_DIAGNOSTICO("IniciarDiagnostico", Destino.EXECUCAO),
    CANCELAR_EXECUCAO("CancelarExecucao", Destino.EXECUCAO),
    INICIAR_REPARO("IniciarReparo", Destino.EXECUCAO),
    GERAR_ORCAMENTO("GerarOrcamento", Destino.BILLING),
    REGISTRAR_DECISAO("RegistrarDecisao", Destino.BILLING),
    CANCELAR_ORCAMENTO("CancelarOrcamento", Destino.BILLING),
    ESTORNAR_PAGAMENTO("EstornarPagamento", Destino.BILLING);

    private final String tipo;
    private final Destino destino;
    Comando(String tipo, Destino destino) { this.tipo = tipo; this.destino = destino; }
    public String tipo() { return tipo; }
    public Destino destino() { return destino; }
}
