package com.oficina.validation;

import com.oficina.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidadorPlacaTest {

    @Test
    void normalizar_removeHifenEspacos() {
        assertThat(ValidadorPlaca.normalizar(" abc-1d23 ")).isEqualTo("ABC1D23");
    }

    @Test
    void validar_aceitaMercosul() {
        ValidadorPlaca.validar("ABC1D23");
    }

    @Test
    void validar_aceitaAntiga() {
        ValidadorPlaca.validar("ABC1234");
    }

    @Test
    void validar_rejeitaInvalida() {
        assertThatThrownBy(() -> ValidadorPlaca.validar("1234567"))
                .isInstanceOf(BusinessRuleException.class);
    }
}
