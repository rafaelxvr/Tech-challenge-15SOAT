package com.oficina.saga.application.port;

import java.util.UUID;

public interface InboxPort {
    /** True only the first time this message id is seen, inside the caller's transaction. */
    boolean registrar(UUID messageId, String tipo);
}
