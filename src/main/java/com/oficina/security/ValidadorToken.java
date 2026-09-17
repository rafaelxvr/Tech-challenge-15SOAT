package com.oficina.security;

public interface ValidadorToken {
    IdentidadeAutenticada validar(String token);
}
