package com.oficina.controller;

import com.oficina.service.OrdemServicoService;
import com.oficina.dto.*;
import com.oficina.entity.StatusOrdemServico;
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
@RequestMapping("/ordens-servico")
@RequiredArgsConstructor
@Tag(name = "Ordens de serviço", description = "Fluxo de OS, orçamento e acompanhamento")
public class OrdemServicoController {

    private final OrdemServicoService ordemServicoService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Criar ordem de serviço (orçamento calculado automaticamente)")
    public ResponseEntity<ApiResponse<OrdemServicoDetalheResponse>> criar(
            @Valid @RequestBody CriarOrdemServicoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Ordem de serviço criada.", ordemServicoService.criar(request)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Listar ordens de serviço")
    public ResponseEntity<ApiResponse<Page<OrdemServicoResumoResponse>>> listar(
            @RequestParam(required = false) StatusOrdemServico status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.listar(status, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Detalhar ordem de serviço por id")
    public ResponseEntity<ApiResponse<OrdemServicoDetalheResponse>> detalhar(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.buscarPorId(id)));
    }

    @GetMapping("/{numero}/acompanhamento")
    @Operation(summary = "Acompanhamento público da OS (cliente)")
    public ResponseEntity<ApiResponse<AcompanhamentoOsResponse>> acompanhamento(
            @PathVariable Long numero) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.acompanhamentoPublico(numero)));
    }

    @PostMapping("/{numero}/aprovar")
    @Operation(summary = "Aprovar orçamento (cliente), com validação de CPF/CNPJ")
    public ResponseEntity<ApiResponse<OrdemServicoDetalheResponse>> aprovar(
            @PathVariable Long numero,
            @Valid @RequestBody AprovacaoClienteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Orçamento aprovado.",
                ordemServicoService.aprovarPeloCliente(numero, request)));
    }

    @PostMapping("/{id}/iniciar-diagnostico")
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Iniciar diagnóstico (RECEBIDA → EM_DIAGNOSTICO)")
    public ResponseEntity<ApiResponse<OrdemServicoDetalheResponse>> iniciarDiagnostico(
            @PathVariable UUID id,
            @RequestBody(required = false) AcaoOrdemRequest acao) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.iniciarDiagnostico(id, acao)));
    }

    @PostMapping("/{id}/enviar-orcamento")
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Enviar orçamento ao cliente (EM_DIAGNOSTICO → AGUARDANDO_APROVACAO)")
    public ResponseEntity<ApiResponse<OrdemServicoDetalheResponse>> enviarOrcamento(
            @PathVariable UUID id,
            @RequestBody(required = false) AcaoOrdemRequest acao) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.enviarOrcamento(id, acao)));
    }

    @PostMapping("/{id}/finalizar")
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Finalizar serviço (EM_EXECUCAO → FINALIZADA)")
    public ResponseEntity<ApiResponse<OrdemServicoDetalheResponse>> finalizar(
            @PathVariable UUID id,
            @RequestBody(required = false) AcaoOrdemRequest acao) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.finalizar(id, acao)));
    }

    @PostMapping("/{id}/entregar")
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Registrar entrega do veículo (FINALIZADA → ENTREGUE)")
    public ResponseEntity<ApiResponse<OrdemServicoDetalheResponse>> entregar(
            @PathVariable UUID id,
            @RequestBody(required = false) AcaoOrdemRequest acao) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.registrarEntrega(id, acao)));
    }
}
