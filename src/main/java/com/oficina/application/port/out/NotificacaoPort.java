package com.oficina.application.port.out;

import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;

/** Persists notification intent atomically with the business transition. */
public interface NotificacaoPort {
    void notificarAtualizacaoStatus(StatusOrdemServicoRegistrado evento);
}
