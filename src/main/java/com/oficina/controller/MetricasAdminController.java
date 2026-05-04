package com.oficina.controller;

import com.oficina.service.MetricasService;
import com.oficina.dto.MetricasTempoResponse;
import com.oficina.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/metricas")
@RequiredArgsConstructor
@Tag(name = "Métricas administrativas", description = "Indicadores operacionais")
@SecurityRequirement(name = "bearerAuth")
public class MetricasAdminController {

    private final MetricasService metricasService;

    @GetMapping("/tempo-execucao-servicos")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Tempo médio estimado (catálogo) vs tempo médio real (OS finalizadas)")
    public ResponseEntity<ApiResponse<MetricasTempoResponse>> tempoExecucao() {
        return ResponseEntity.ok(ApiResponse.success(metricasService.tempoMedioExecucao()));
    }
}
