package com.oficina.validation;

import com.oficina.exception.BusinessRuleException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.regex.Pattern;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ValidadorPlaca {

    private static final Pattern MERCOSUL = Pattern.compile("^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$");
    private static final Pattern ANTIGA = Pattern.compile("^[A-Z]{3}[0-9]{4}$");

    public static String normalizar(String placa) {
        if (placa == null) {
            return null;
        }
        return placa.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT).trim();
    }

    public static void validar(String placaNormalizada) {
        if (placaNormalizada == null || placaNormalizada.isBlank()) {
            throw new BusinessRuleException("Placa é obrigatória.");
        }
        if (placaNormalizada.length() > 8) {
            throw new BusinessRuleException("Placa inválida.");
        }
        if (!MERCOSUL.matcher(placaNormalizada).matches() && !ANTIGA.matcher(placaNormalizada).matches()) {
            throw new BusinessRuleException(
                    "Placa inválida. Use o padrão Mercosul (ABC1D23) ou antigo (ABC1234)."
            );
        }
    }
}
