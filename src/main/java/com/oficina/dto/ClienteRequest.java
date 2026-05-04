package com.oficina.dto;

import com.oficina.entity.TipoDocumento;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClienteRequest(
        @NotBlank String nome,
        TipoDocumento tipoDocumento,
        @NotBlank String documento,
        @NotBlank @Email String email,
        @NotBlank @Size(max = 20) String telefone,
        @Size(max = 9) String cep,
        @Size(max = 255) String logradouro,
        @Size(max = 20) String numero,
        @Size(max = 100) String complemento,
        @Size(max = 100) String bairro,
        @Size(max = 100) String cidade,
        @Size(min = 2, max = 2) String estado
) {}
