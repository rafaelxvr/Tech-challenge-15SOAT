package com.oficina.entity;

import com.oficina.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PecaTest {

    @Test
    void baixarEstoque_atualizaQuantidade() {
        Peca p = Peca.builder()
                .codigo("T-1")
                .nome("Teste")
                .valorUnitario(BigDecimal.TEN)
                .quantidadeEstoque(5)
                .quantidadeMinima(1)
                .unidadeMedida("UN")
                .ativo(true)
                .build();
        p.baixarEstoque(2);
        assertThat(p.getQuantidadeEstoque()).isEqualTo(3);
    }

    @Test
    void baixarEstoque_semSaldo_lancaRegra() {
        Peca p = Peca.builder()
                .codigo("T-2")
                .nome("Teste")
                .valorUnitario(BigDecimal.TEN)
                .quantidadeEstoque(1)
                .quantidadeMinima(1)
                .unidadeMedida("UN")
                .ativo(true)
                .build();
        assertThatThrownBy(() -> p.baixarEstoque(3))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reporEstoque_somaQuantidade() {
        Peca p = Peca.builder()
                .codigo("T-3")
                .nome("Teste")
                .valorUnitario(BigDecimal.TEN)
                .quantidadeEstoque(2)
                .quantidadeMinima(1)
                .unidadeMedida("UN")
                .ativo(true)
                .build();
        p.reporEstoque(4);
        assertThat(p.getQuantidadeEstoque()).isEqualTo(6);
    }

    @Test
    void baixarEstoque_quantidadeInvalida_lanca() {
        Peca p = Peca.builder()
                .codigo("T-4")
                .nome("Teste")
                .valorUnitario(BigDecimal.TEN)
                .quantidadeEstoque(5)
                .quantidadeMinima(1)
                .unidadeMedida("UN")
                .ativo(true)
                .build();
        assertThatThrownBy(() -> p.baixarEstoque(0)).isInstanceOf(BusinessRuleException.class);
    }
}
