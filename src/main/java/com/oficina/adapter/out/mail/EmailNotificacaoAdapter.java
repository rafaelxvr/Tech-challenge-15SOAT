package com.oficina.adapter.out.mail;

import com.oficina.application.port.out.NotificacaoPort;
import com.oficina.entity.OrdemServico;
import com.oficina.entity.StatusOrdemServico;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Adaptador de saída: notifica atualizações de status da OS por e-mail (SMTP).
 * Em desenvolvimento local, use MailHog (docker-compose profile tools).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificacaoAdapter implements NotificacaoPort {

    private final JavaMailSender mailSender;

    @Value("${oficina.mail.from:noreply@oficina.com}")
    private String from;

    @Value("${oficina.mail.enabled:true}")
    private boolean enabled;

    @Override
    public void notificarAtualizacaoStatus(OrdemServico ordem, StatusOrdemServico statusAnterior, String mensagem) {
        if (!enabled) {
            log.debug("Notificação por e-mail desabilitada. OS #{} {} → {}",
                    ordem.getNumero(), statusAnterior, ordem.getStatus());
            return;
        }

        String destino = ordem.getCliente() != null ? ordem.getCliente().getEmail() : null;
        if (destino == null || destino.isBlank()) {
            log.warn("OS #{} sem e-mail de cliente; notificação ignorada.", ordem.getNumero());
            return;
        }

        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(destino);
            mail.setSubject("Atualização da OS #" + ordem.getNumero() + " — " + ordem.getStatus());
            mail.setText("""
                    Olá, %s!

                    A ordem de serviço #%d teve o status atualizado.
                    Status anterior: %s
                    Status atual: %s
                    Veículo: %s
                    Mensagem: %s

                    Acompanhe em: /api/ordens-servico/%d/acompanhamento
                    """.formatted(
                    ordem.getCliente().getNome(),
                    ordem.getNumero(),
                    statusAnterior != null ? statusAnterior.name() : "-",
                    ordem.getStatus(),
                    ordem.getVeiculo() != null ? ordem.getVeiculo().getPlaca() : "-",
                    mensagem != null ? mensagem : "-",
                    ordem.getNumero()
            ));
            mailSender.send(mail);
            log.info("E-mail de status enviado para {} (OS #{})", destino, ordem.getNumero());
        } catch (Exception ex) {
            // Não interrompe o fluxo de negócio se o SMTP falhar (ex.: MailHog offline)
            log.error("Falha ao enviar e-mail da OS #{}: {}", ordem.getNumero(), ex.getMessage());
        }
    }
}
