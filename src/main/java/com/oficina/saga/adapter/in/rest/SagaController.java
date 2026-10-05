package com.oficina.saga.adapter.in.rest;

import com.oficina.dto.ApiResponse;
import com.oficina.exception.EntityNotFoundException;
import com.oficina.saga.application.CicloOsSagaService;
import com.oficina.saga.application.SagaDetalhe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/ordens-servico")
@RequiredArgsConstructor
@Tag(name = "Saga da OS", description = "Estado e trilha da saga distribuída de uma ordem de serviço")
public class SagaController {
    private final CicloOsSagaService ciclo;

    @GetMapping("/{id}/saga")
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Estado atual, compensações pendentes e trilha de passos da saga")
    public ResponseEntity<ApiResponse<SagaDetalhe>> detalhar(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ciclo.detalhar(id)
                .orElseThrow(() -> new EntityNotFoundException("Saga da ordem de serviço", id))));
    }
}
