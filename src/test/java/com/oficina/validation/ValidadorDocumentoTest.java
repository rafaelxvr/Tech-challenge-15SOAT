package com.oficina.validation;

import com.oficina.entity.TipoDocumento;
import com.oficina.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidadorDocumentoTest {

    @Test
    void normalizar_removePontuacao() {
        assertThat(ValidadorDocumento.normalizarDigitos("123.456.789-09")).isEqualTo("12345678909");
    }

    @Test
    void inferirTipo_cpf() {
        assertThat(ValidadorDocumento.inferirTipo("12345678909")).isEqualTo(TipoDocumento.CPF);
    }

    @Test
    void validar_cpfConhecidoValido() {
        ValidadorDocumento.validarCpfOuCnpj("39053344705");
    }

    @Test
    void validar_cpfInvalido() {
        assertThatThrownBy(() -> ValidadorDocumento.validarCpfOuCnpj("11111111111"))
                .isInstanceOf(BusinessRuleException.class);
    }
}
