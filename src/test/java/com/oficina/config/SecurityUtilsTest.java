package com.oficina.config;

import com.oficina.entity.Usuario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityUtilsTest {

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void semAutenticacao_retornaVazio() {
        assertThat(SecurityUtils.usuarioAutenticadoId()).isEmpty();
    }

    @Test
    void principalUsuario_retornaId() {
        UUID id = UUID.randomUUID();
        Usuario u = Usuario.builder()
                .id(id)
                .email("e@e.com")
                .senha("s")
                .role(Usuario.Role.MECANICO)
                .ativo(true)
                .build();
        var auth = new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SecurityUtils.usuarioAutenticadoId()).contains(id);
    }

    @Test
    void principalNaoUsuario_retornaVazio() {
        var user = new User("x", "p", Collections.emptyList());
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SecurityUtils.usuarioAutenticadoId()).isEmpty();
    }

    @Test
    void autenticadoFalse_retornaVazio() {
        var auth = new UsernamePasswordAuthenticationToken("anon", null, Collections.emptyList());
        auth.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SecurityUtils.usuarioAutenticadoId()).isEmpty();
    }
}
