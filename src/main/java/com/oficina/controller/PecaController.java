package com.oficina.controller;

import com.oficina.service.PecaService;
import com.oficina.dto.PecaRequest;
import com.oficina.dto.PecaResponse;
import com.oficina.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/pecas")
@RequiredArgsConstructor
@Tag(name = "Peças e estoque", description = "CRUD de peças com controle de estoque")
@SecurityRequirement(name = "bearerAuth")
public class PecaController {

    private final PecaService pecaService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar peças ativas")
    public ResponseEntity<ApiResponse<Page<PecaResponse>>> listar(
            @PageableDefault(size = 50, sort = "nome", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(pecaService.listar(pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Buscar peça por id")
    public ResponseEntity<ApiResponse<PecaResponse>> buscar(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(pecaService.buscar(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cadastrar peça")
    public ResponseEntity<ApiResponse<PecaResponse>> criar(@Valid @RequestBody PecaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Peça cadastrada.", pecaService.criar(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Atualizar peça")
    public ResponseEntity<ApiResponse<PecaResponse>> atualizar(
            @PathVariable UUID id,
            @Valid @RequestBody PecaRequest request) {
        return ResponseEntity.ok(ApiResponse.success(pecaService.atualizar(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Desativar peça")
    public ResponseEntity<ApiResponse<Void>> desativar(@PathVariable UUID id) {
        pecaService.desativar(id);
        return ResponseEntity.ok(ApiResponse.success("Peça desativada.", null));
    }
}
