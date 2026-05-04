package com.oficina.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CriarOrdemServicoRequest(
        @NotBlank String documentoCliente,
        @NotBlank String placaVeiculo,
        String marca,
        String modelo,
        Integer ano,
        @NotEmpty List<@Valid ItemServicoOsRequest> servicos,
        List<@Valid ItemPecaOsRequest> pecas,
        String observacoes
) {}
