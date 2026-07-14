package com.oficina.application.port.out;

import com.oficina.entity.OrdemServico;
import com.oficina.entity.StatusOrdemServico;

/**
 * Porta de saída (hexagonal) para notificar mudanças de status da OS.
 * Adaptadores: e-mail (SMTP/MailHog), futuros webhooks, etc.
 */
public interface NotificacaoPort {

    void notificarAtualizacaoStatus(OrdemServico ordem, StatusOrdemServico statusAnterior, String mensagem);
}
