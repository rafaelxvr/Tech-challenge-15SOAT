package com.oficina.saga.adapter.out.persistence;

import com.oficina.saga.application.mensagem.Envelope;
import com.oficina.saga.application.mensagem.MapeadorMensagens;
import com.oficina.saga.application.port.SaidaMensagensPort;
import com.oficina.saga.domain.Destino;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class JdbcSagaOutbox implements SaidaMensagensPort {
    private final JdbcTemplate jdbc;
    private final MapeadorMensagens mapeador;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(Destino destino, Envelope envelope) {
        String corpo = mapeador.escrever(envelope);
        if (corpo.getBytes(StandardCharsets.UTF_8).length > Envelope.MAX_BYTES) {
            throw new IllegalArgumentException("Envelope acima de 64 KB");
        }
        jdbc.update("""
                INSERT INTO saga_outbox(message_id, os_id, destino, tipo, envelope) VALUES (?,?,?,?,?::jsonb)
                """, envelope.messageId(), envelope.osId(), destino.name(), envelope.type(), corpo);
    }
}
