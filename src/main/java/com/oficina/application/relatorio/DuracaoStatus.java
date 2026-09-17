package com.oficina.application.relatorio;

import java.math.BigDecimal;

/** Totals and order samples, never an average of individual transition intervals. */
public record DuracaoStatus(BigDecimal totalSegundos, long amostras) {}
