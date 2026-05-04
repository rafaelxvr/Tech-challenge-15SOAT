package com.oficina.validation;

import br.com.caelum.stella.format.CNPJFormatter;
import br.com.caelum.stella.format.CPFFormatter;
import br.com.caelum.stella.validation.CNPJValidator;
import br.com.caelum.stella.validation.CPFValidator;
import br.com.caelum.stella.validation.InvalidStateException;
import com.oficina.entity.TipoDocumento;
import com.oficina.exception.BusinessRuleException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ValidadorDocumento {

    private static final CPFValidator CPF = new CPFValidator(false);
    private static final CNPJValidator CNPJ = new CNPJValidator(false);

    public static String normalizarDigitos(String documento) {
        if (documento == null) {
            return null;
        }
        return documento.replaceAll("\\D", "");
    }

    public static TipoDocumento inferirTipo(String apenasDigitos) {
        if (apenasDigitos == null) {
            throw new BusinessRuleException("Documento é obrigatório.");
        }
        return switch (apenasDigitos.length()) {
            case 11 -> TipoDocumento.CPF;
            case 14 -> TipoDocumento.CNPJ;
            default -> throw new BusinessRuleException(
                    "Documento deve ter 11 dígitos (CPF) ou 14 dígitos (CNPJ)."
            );
        };
    }

    public static void validarCpfOuCnpj(String apenasDigitos) {
        if (apenasDigitos == null || apenasDigitos.isBlank()) {
            throw new BusinessRuleException("Documento é obrigatório.");
        }
        try {
            if (apenasDigitos.length() == 11) {
                CPF.assertValid(apenasDigitos);
            } else if (apenasDigitos.length() == 14) {
                CNPJ.assertValid(apenasDigitos);
            } else {
                throw new BusinessRuleException(
                        "Documento deve ter 11 dígitos (CPF) ou 14 dígitos (CNPJ)."
                );
            }
        } catch (InvalidStateException ex) {
            throw new BusinessRuleException("CPF ou CNPJ inválido.");
        }
    }

    public static String formatarParaExibicao(String apenasDigitos) {
        if (apenasDigitos == null) {
            return null;
        }
        try {
            if (apenasDigitos.length() == 11) {
                return new CPFFormatter().format(apenasDigitos);
            }
            if (apenasDigitos.length() == 14) {
                return new CNPJFormatter().format(apenasDigitos);
            }
        } catch (Exception ignored) {
            return apenasDigitos;
        }
        return apenasDigitos;
    }
}
