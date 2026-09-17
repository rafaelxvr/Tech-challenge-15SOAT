package com.oficina.controller;

import com.oficina.service.OrdemServicoService;
import com.oficina.security.IdentidadeAutenticada;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.oficina.dto.*;
import com.oficina.entity.StatusOrdemServico;
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
    @Operation(summary = "Abertura de OS — cliente, veículo, serviços e peças; retorna identificação única")
    public ResponseEntity<ApiResponse<OrdemServicoDetalheResponse>> criar(
            @Valid @RequestBody CriarOrdemServicoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Ordem de serviço criada.", ordemServicoService.criar(request)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Listar OS ativas (exclui FINALIZADA/ENTREGUE), prioridade de status e mais antigas primeiro")
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
    @PreAuthorize("hasAuthority('SCOPE_orders:read:self')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Consultar status e orçamento da própria OS; requer customer e orders:read:self")
    public ResponseEntity<ApiResponse<AcompanhamentoOsResponse>> acompanhamento(
            @PathVariable Long numero, @AuthenticationPrincipal IdentidadeAutenticada cliente) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.acompanhamentoDoCliente(numero, cliente)));
    }

    @PostMapping("/{numero}/orcamento/decisao")
    @PreAuthorize("hasAuthority('SCOPE_orders:decide:self')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Decidir sobre o próprio orçamento; requer customer e orders:decide:self")
    public ResponseEntity<ApiResponse<AcompanhamentoOsResponse>> decisaoOrcamento(
            @PathVariable Long numero, @AuthenticationPrincipal IdentidadeAutenticada cliente,
            @Valid @RequestBody DecisaoClienteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.decidirComoCliente(numero, cliente, request)));
    }

    @PostMapping("/{numero}/orcamento/notificacao")
    @PreAuthorize("hasAuthority('SCOPE_orders:decide:self')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Alias autenticado de /orcamento/decisao; documento deve concordar com o cliente", deprecated = true)
    public ResponseEntity<ApiResponse<AcompanhamentoOsResponse>> notificacaoOrcamento(
            @PathVariable Long numero, @AuthenticationPrincipal IdentidadeAutenticada cliente,
            @Valid @RequestBody DecisaoOrcamentoRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                ordemServicoService.decidirComoClienteLegado(numero, cliente, request)));
    }

    @PostMapping("/{numero}/aprovar")
    @PreAuthorize("hasAuthority('SCOPE_orders:decide:self')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Alias autenticado de aprovação; preferir /orcamento/decisao", deprecated = true)
    public ResponseEntity<ApiResponse<AcompanhamentoOsResponse>> aprovar(
            @PathVariable Long numero, @AuthenticationPrincipal IdentidadeAutenticada cliente,
            @Valid @RequestBody AprovacaoClienteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(ordemServicoService.decidirComoClienteLegado(numero, cliente,
                new DecisaoOrcamentoRequest(DecisaoOrcamentoRequest.DecisaoOrcamento.APROVADO,
                        request.documentoCliente(), null))));
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
