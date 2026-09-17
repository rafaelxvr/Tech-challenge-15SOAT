package com.oficina.adapter.out.mail;

import com.oficina.application.notificacao.StatusOrdemServicoRegistrado;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Optional downstream MailHog test sink. Never selected as the business notification port. */
@Component
@Profile("local-mailhog")
@RequiredArgsConstructor
public class EmailNotificacaoAdapter {
    private final JavaMailSender mailSender;
    @Value("${oficina.mail.from:noreply@oficina.com}") private String from;

    @Transactional(propagation = Propagation.NEVER)
    public void enviar(StatusOrdemServicoRegistrado evento, String destinatario) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(destinatario);
        mail.setSubject("Atualização da OS #" + evento.numero() + " — " + evento.statusNovo());
        mail.setText("A ordem de serviço #%d está em %s. Acompanhe em /api/ordens-servico/%d/acompanhamento"
                .formatted(evento.numero(), evento.statusNovo(), evento.numero()));
        mailSender.send(mail);
    }
}
