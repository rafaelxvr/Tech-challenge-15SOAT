package com.oficina.dto;

import jakarta.validation.constraints.NotBlank;

public record AprovacaoClienteRequest(
        @NotBlank String documentoCliente
) {}
