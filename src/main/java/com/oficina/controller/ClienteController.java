package com.oficina.controller;

import com.oficina.service.ClienteService;
import com.oficina.dto.ClienteRequest;
import com.oficina.dto.ClienteResponse;
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
@RequestMapping("/clientes")
@RequiredArgsConstructor
@Tag(name = "Clientes", description = "CRUD de clientes")
@SecurityRequirement(name = "bearerAuth")
public class ClienteController {

    private final ClienteService clienteService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @Operation(summary = "Listar clientes ativos")
    public ResponseEntity<ApiResponse<Page<ClienteResponse>>> listar(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(clienteService.listar(pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @Operation(summary = "Buscar cliente por id")
    public ResponseEntity<ApiResponse<ClienteResponse>> buscar(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(clienteService.buscar(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cadastrar cliente")
    public ResponseEntity<ApiResponse<ClienteResponse>> criar(@Valid @RequestBody ClienteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Cliente cadastrado.", clienteService.criar(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Atualizar cliente")
    public ResponseEntity<ApiResponse<ClienteResponse>> atualizar(
            @PathVariable UUID id,
            @Valid @RequestBody ClienteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(clienteService.atualizar(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Desativar cliente")
    public ResponseEntity<ApiResponse<Void>> desativar(@PathVariable UUID id) {
        clienteService.desativar(id);
        return ResponseEntity.ok(ApiResponse.success("Cliente desativado.", null));
    }
}
