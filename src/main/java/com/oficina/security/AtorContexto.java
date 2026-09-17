package com.oficina.security;

import com.oficina.domain.identidade.Ator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AtorContexto {
    public Ator atual() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof IdentidadeAutenticada identidade)) {
            throw TokenVerification.invalid();
        }
        return identidade.tipo() == TipoPrincipal.STAFF ? Ator.staff(identidade.id()) : Ator.cliente(identidade.id());
    }
}
