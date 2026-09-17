package com.oficina.application.relatorio;

import com.oficina.entity.StatusOrdemServico;
import java.math.BigDecimal;

/** A null maximum means that no age is known; zero is a measured zero. */
public record StatusAtual(StatusOrdemServico status, long quantidade, BigDecimal idadeMaximaSegundos,
                          long amostrasIdade, long idadesDesconhecidas) {}
