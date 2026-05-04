package com.oficina.exception;

public class DuplicateEntityException extends DomainException {

    public DuplicateEntityException(String entity, String field, Object value) {
        super(
            String.format("%s com %s '%s' já está cadastrado.", entity, field, value),
            "DUPLICATE_ENTITY"
        );
    }
}
