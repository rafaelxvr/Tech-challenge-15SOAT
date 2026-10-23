package com.oficina.saga.domain;

/** Effects the saga asks os-service to apply to its own aggregates in the same transaction. */
public enum AcaoLocal {
    INICIAR_DIAGNOSTICO_OS, ENVIAR_ORCAMENTO_OS, AGUARDAR_PAGAMENTO_OS, VOLTAR_DIAGNOSTICO_OS,
    INICIAR_EXECUCAO_OS, FINALIZAR_OS, REPOR_ESTOQUE, CANCELAR_OS
}
