package com.oficina.application.relatorio;

import com.oficina.entity.StatusOrdemServico;
import java.util.Map;

public record RelatorioPeriodo(long criadas, long elegiveis, long excluidas,
                               Map<StatusOrdemServico, DuracaoStatus> duracoes) {
    public RelatorioPeriodo {
        duracoes = Map.copyOf(duracoes);
    }
}
