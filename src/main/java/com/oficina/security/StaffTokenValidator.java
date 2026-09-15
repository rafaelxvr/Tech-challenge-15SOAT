package com.oficina.security;

import com.oficina.config.JwtService;
import com.oficina.entity.Usuario;
import com.oficina.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class StaffTokenValidator implements ValidadorToken {
    private final JwtService jwtService;
    private final UsuarioRepository usuarios;

    @Override public IdentidadeAutenticada validar(String token) {
        var claims = jwtService.validarAccessToken(token);
        Usuario usuario = usuarios.findByEmail(claims.getSubject()).orElseThrow(TokenVerification::invalid);
        if (!usuario.isAtivo() || usuario.getId() == null
                || (usuario.getRole() != Usuario.Role.ADMIN && usuario.getRole() != Usuario.Role.MECANICO)) {
            throw TokenVerification.invalid();
        }
        // Signed roles are informational. The current database role is authoritative on every request.
        return new IdentidadeAutenticada(TipoPrincipal.STAFF, usuario.getId(),
                Set.of("ROLE_" + usuario.getRole().name()), 0);
    }
}
