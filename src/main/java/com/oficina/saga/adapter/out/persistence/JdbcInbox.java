package com.oficina.saga.adapter.out.persistence;

import com.oficina.saga.application.port.InboxPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JdbcInbox implements InboxPort {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean registrar(UUID messageId, String tipo) {
        return jdbc.update("INSERT INTO saga_inbox(message_id, tipo) VALUES (?,?) ON CONFLICT (message_id) DO NOTHING",
                messageId, tipo) == 1;
    }
}
