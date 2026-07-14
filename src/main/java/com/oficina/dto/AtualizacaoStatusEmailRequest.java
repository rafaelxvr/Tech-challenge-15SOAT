package com.oficina.dto;

import com.oficina.entity.StatusOrdemServico;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Payload que simula atualização de status disparada por ferramenta de e-mail
 * (link no corpo do e-mail, automação Mailgun/SendGrid, etc.).
 */
public record AtualizacaoStatusEmailRequest(
        @NotNull Long numero,
        @NotNull StatusOrdemServico novoStatus,
        @NotBlank String token,
        String observacao
) {}
