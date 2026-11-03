package com.oficina.saga.domain;

import java.time.Duration;
import java.util.stream.Stream;

public record PrazosSaga(Duration diagnostico, Duration respostaAutomatica, Duration decisao,
                         Duration pagamento, Duration compensacao, int maxTentativasCompensacao) {
    public PrazosSaga {
        if (Stream.of(diagnostico, respostaAutomatica, decisao, pagamento, compensacao)
                .anyMatch(d -> d == null || d.isNegative() || d.isZero())) {
            throw new IllegalArgumentException("Prazos da saga devem ser positivos");
        }
        if (maxTentativasCompensacao < 1) {
            throw new IllegalArgumentException("Ao menos uma tentativa de compensação é obrigatória");
        }
    }
}
