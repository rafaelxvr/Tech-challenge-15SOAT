package com.oficina.exception;

public class EntityNotFoundException extends DomainException {

    public EntityNotFoundException(String entity, Object id) {
        super(String.format("%s com id '%s' não encontrado.", entity, id), "ENTITY_NOT_FOUND");
    }

    public EntityNotFoundException(String message) {
        super(message, "ENTITY_NOT_FOUND");
    }
}
