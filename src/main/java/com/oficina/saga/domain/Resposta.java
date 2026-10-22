package com.oficina.saga.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Reply types; each is bound to the command(s) it may answer (the {@code answers} field of the v1 contract). */
public enum Resposta {
    DIAGNOSTICO_CONCLUIDO("DiagnosticoConcluido", Comando.INICIAR_DIAGNOSTICO),
    DIAGNOSTICO_RECUSADO("DiagnosticoRecusado", Comando.INICIAR_DIAGNOSTICO),
    EXECUCAO_CANCELADA("ExecucaoCancelada", Comando.CANCELAR_EXECUCAO),
    ORCAMENTO_GERADO("OrcamentoGerado", Comando.GERAR_ORCAMENTO),
    ORCAMENTO_FALHOU("OrcamentoFalhou", Comando.GERAR_ORCAMENTO),
    ORCAMENTO_APROVADO("OrcamentoAprovado", Comando.REGISTRAR_DECISAO),
    ORCAMENTO_RECUSADO("OrcamentoRecusado", Comando.REGISTRAR_DECISAO),
    ORCAMENTO_CANCELADO("OrcamentoCancelado", Comando.CANCELAR_ORCAMENTO),
    PAGAMENTO_CONFIRMADO("PagamentoConfirmado", Comando.REGISTRAR_DECISAO),
    PAGAMENTO_RECUSADO("PagamentoRecusado", Comando.REGISTRAR_DECISAO),
    PAGAMENTO_EXPIRADO("PagamentoExpirado", Comando.REGISTRAR_DECISAO),
    PAGAMENTO_ESTORNADO("PagamentoEstornado", Comando.ESTORNAR_PAGAMENTO),
    REPARO_INICIADO("ReparoIniciado", Comando.INICIAR_REPARO),
    REPARO_RECUSADO("ReparoRecusado", Comando.INICIAR_REPARO),
    EXECUCAO_FINALIZADA("ExecucaoFinalizada", Comando.INICIAR_REPARO),
    COMPENSACAO_FALHOU("CompensacaoFalhou", Comando.CANCELAR_ORCAMENTO, Comando.ESTORNAR_PAGAMENTO,
            Comando.CANCELAR_EXECUCAO);

    private final String tipo;
    private final Set<Comando> responde;

    Resposta(String tipo, Comando primeiro, Comando... demais) {
        this.tipo = tipo;
        this.responde = EnumSet.of(primeiro, demais);
    }

    public String tipo() { return tipo; }

    /** True when this reply type may answer {@code comando}; anything else is a mismatched (spoofed or buggy) reply. */
    public boolean respondeA(Comando comando) { return comando != null && responde.contains(comando); }

    public static Optional<Resposta> deTipo(String tipo) {
        return Arrays.stream(values()).filter(r -> r.tipo.equals(tipo)).findFirst();
    }
}
