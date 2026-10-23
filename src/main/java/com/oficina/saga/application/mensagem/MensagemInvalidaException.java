package com.oficina.saga.application.mensagem;

public class MensagemInvalidaException extends RuntimeException {
    public MensagemInvalidaException(String mensagem) { super(mensagem); }
}
