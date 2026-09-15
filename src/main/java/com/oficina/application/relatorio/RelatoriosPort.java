package com.oficina.application.relatorio;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Materialized SQL projections; callers receive no open cursor or transaction. */
public interface RelatoriosPort {
    RelatorioPeriodo consultar(LocalDate inicio, LocalDate fimExclusive, ZoneId zona);
    List<StatusAtual> statusAtual(Instant agora);
}
