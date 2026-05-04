package com.oficina.controller;

import com.oficina.service.VeiculoService;
import com.oficina.dto.VeiculoRequest;
import com.oficina.dto.VeiculoResponse;
import com.oficina.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/veiculos")
@RequiredArgsConstructor
@Tag(name = "Veículos", description = "CRUD de veículos")
@SecurityRequirement(name = "bearerAuth")
public class VeiculoController {

    private final VeiculoService veiculoService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @Operation(summary = "Listar veículos ativos")
    public ResponseEntity<ApiResponse<Page<VeiculoResponse>>> listar(
            @RequestParam(required = false) UUID clienteId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<VeiculoResponse> page = clienteId == null
                ? veiculoService.listar(pageable)
                : veiculoService.listarPorCliente(clienteId, pageable);
        return ResponseEntity.ok(ApiResponse.success(page));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @Operation(summary = "Buscar veículo por id")
    public ResponseEntity<ApiResponse<VeiculoResponse>> buscar(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(veiculoService.buscar(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @Operation(summary = "Cadastrar veículo")
    public ResponseEntity<ApiResponse<VeiculoResponse>> criar(@Valid @RequestBody VeiculoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Veículo cadastrado.", veiculoService.criar(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @Operation(summary = "Atualizar veículo")
    public ResponseEntity<ApiResponse<VeiculoResponse>> atualizar(
            @PathVariable UUID id,
            @Valid @RequestBody VeiculoRequest request) {
        return ResponseEntity.ok(ApiResponse.success(veiculoService.atualizar(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Desativar veículo")
    public ResponseEntity<ApiResponse<Void>> desativar(@PathVariable UUID id) {
        veiculoService.desativar(id);
        return ResponseEntity.ok(ApiResponse.success("Veículo desativado.", null));
    }
}
