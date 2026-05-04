package com.oficina.controller;

import com.oficina.service.ServicoService;
import com.oficina.dto.ServicoRequest;
import com.oficina.dto.ServicoResponse;
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
@RequestMapping("/servicos")
@RequiredArgsConstructor
@Tag(name = "Serviços (catálogo)", description = "CRUD do catálogo de serviços da oficina")
@SecurityRequirement(name = "bearerAuth")
public class ServicoCatalogoController {

    private final ServicoService servicoService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Listar serviços ativos")
    public ResponseEntity<ApiResponse<Page<ServicoResponse>>> listar(
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(servicoService.listar(pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Buscar serviço por id")
    public ResponseEntity<ApiResponse<ServicoResponse>> buscar(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(servicoService.buscar(id)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cadastrar serviço")
    public ResponseEntity<ApiResponse<ServicoResponse>> criar(@Valid @RequestBody ServicoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Serviço cadastrado.", servicoService.criar(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Atualizar serviço")
    public ResponseEntity<ApiResponse<ServicoResponse>> atualizar(
            @PathVariable UUID id,
            @Valid @RequestBody ServicoRequest request) {
        return ResponseEntity.ok(ApiResponse.success(servicoService.atualizar(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Desativar serviço")
    public ResponseEntity<ApiResponse<Void>> desativar(@PathVariable UUID id) {
        servicoService.desativar(id);
        return ResponseEntity.ok(ApiResponse.success("Serviço desativado.", null));
    }
}
