package com.oficina.saga.domain;

import java.util.Arrays;
import java.util.Optional;

public enum Resposta {
    DIAGNOSTICO_CONCLUIDO("DiagnosticoConcluido"), DIAGNOSTICO_RECUSADO("DiagnosticoRecusado"),
    EXECUCAO_CANCELADA("ExecucaoCancelada"),
    ORCAMENTO_GERADO("OrcamentoGerado"), ORCAMENTO_FALHOU("OrcamentoFalhou"),
    ORCAMENTO_APROVADO("OrcamentoAprovado"), ORCAMENTO_RECUSADO("OrcamentoRecusado"),
    ORCAMENTO_CANCELADO("OrcamentoCancelado"),
    PAGAMENTO_CONFIRMADO("PagamentoConfirmado"), PAGAMENTO_RECUSADO("PagamentoRecusado"),
    PAGAMENTO_EXPIRADO("PagamentoExpirado"), PAGAMENTO_ESTORNADO("PagamentoEstornado"),
    REPARO_INICIADO("ReparoIniciado"), REPARO_RECUSADO("ReparoRecusado"),
    EXECUCAO_FINALIZADA("ExecucaoFinalizada"), COMPENSACAO_FALHOU("CompensacaoFalhou");

    private final String tipo;
    Resposta(String tipo) { this.tipo = tipo; }
    public String tipo() { return tipo; }

    public static Optional<Resposta> deTipo(String tipo) {
        return Arrays.stream(values()).filter(r -> r.tipo.equals(tipo)).findFirst();
    }
}
