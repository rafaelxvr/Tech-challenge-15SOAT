package com.oficina.controller;

import com.oficina.application.relatorio.RelatorioPeriodo;
import com.oficina.application.relatorio.RelatoriosPort;
import com.oficina.dto.ApiResponse;
import com.oficina.entity.StatusOrdemServico;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/relatorios")
public class RelatoriosAdminController {
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private final RelatoriosPort relatorios;

    public RelatoriosAdminController(RelatoriosPort relatorios) { this.relatorios = relatorios; }

    @GetMapping("/ordens")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> consultar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fimExclusive) {
        if (!fimExclusive.isAfter(inicio)) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_PERIOD",
                    "message", "fimExclusive deve ser posterior a inicio"));
        }
        RelatorioPeriodo report = relatorios.consultar(inicio, fimExclusive, ZONA);
        var duracoes = new EnumMap<StatusOrdemServico, DuracaoResponse>(StatusOrdemServico.class);
        report.duracoes().forEach((status, duracao) -> duracoes.put(status,
                new DuracaoResponse(duracao.totalSegundos(), duracao.amostras(),
                        duracao.amostras() == 0 ? "N/A" : duracao.totalSegundos()
                                .divide(BigDecimal.valueOf(duracao.amostras()), 6, RoundingMode.HALF_UP)
                                .stripTrailingZeros().toPlainString())));
        return ResponseEntity.ok(ApiResponse.success(new PeriodoResponse(report.criadas(), report.elegiveis(),
                report.excluidas(), duracoes)));
    }

    public record DuracaoResponse(BigDecimal totalSegundos, long amostras, String mediaSegundos) {}
    public record PeriodoResponse(long criadas, long elegiveis, long excluidas,
                                  Map<StatusOrdemServico, DuracaoResponse> duracoes) {}

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<?> periodoInvalido() {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_PERIOD",
                "message", "Informe inicio e fimExclusive no formato YYYY-MM-DD"));
    }
}
