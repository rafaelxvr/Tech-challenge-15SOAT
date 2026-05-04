package com.oficina.dto;

import com.oficina.entity.TipoDocumento;

import java.util.UUID;

public record ClienteResponse(
        UUID id,
        String nome,
        TipoDocumento tipoDocumento,
        String documentoFormatado,
        String documentoSomenteDigitos,
        String email,
        String telefone,
        String cep,
        String logradouro,
        String numero,
        String complemento,
        String bairro,
        String cidade,
        String estado,
        boolean ativo
) {}
