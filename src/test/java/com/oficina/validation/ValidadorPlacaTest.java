package com.oficina.validation;

import com.oficina.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidadorPlacaTest {

    @Test
    void normalizar_removeHifenEspacos() {
        assertThat(ValidadorPlaca.normalizar(" abc-1d23 ")).isEqualTo("ABC1D23");
    }

    @Test
    void validar_aceitaMercosul() {
        assertThatCode(() -> ValidadorPlaca.validar("ABC1D23")).doesNotThrowAnyException();
    }

    @Test
    void validar_aceitaAntiga() {
        assertThatCode(() -> ValidadorPlaca.validar("ABC1234")).doesNotThrowAnyException();
    }

    @Test
    void validar_rejeitaInvalida() {
        assertThatThrownBy(() -> ValidadorPlaca.validar("1234567"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void normalizar_nullRetornaNull() {
        assertThat(ValidadorPlaca.normalizar(null)).isNull();
    }

    @Test
    void validar_nullLanca() {
        assertThatThrownBy(() -> ValidadorPlaca.validar(null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void validar_blankLanca() {
        assertThatThrownBy(() -> ValidadorPlaca.validar("   "))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void validar_tamanhoMaiorQue8Lanca() {
        assertThatThrownBy(() -> ValidadorPlaca.validar("ABCDEFGH1"))
                .isInstanceOf(BusinessRuleException.class);
    }
}
