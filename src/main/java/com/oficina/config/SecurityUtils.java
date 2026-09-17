package com.oficina.config;

import com.oficina.entity.Usuario;
import com.oficina.security.IdentidadeAutenticada;
import com.oficina.security.TipoPrincipal;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SecurityUtils {

    public static Optional<UUID> usuarioAutenticadoId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof Usuario usuario) {
            return Optional.ofNullable(usuario.getId());
        }
        if (auth.getPrincipal() instanceof IdentidadeAutenticada identidade && identidade.tipo() == TipoPrincipal.STAFF) {
            return Optional.of(identidade.id());
        }
        return Optional.empty();
    }
}
