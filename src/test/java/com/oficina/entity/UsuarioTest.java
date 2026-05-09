package com.oficina.entity;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UsuarioTest {

    @Test
    void userDetails_mapeiaRoleParaAuthority() {
        Usuario u = Usuario.builder()
                .id(UUID.randomUUID())
                .email("a@a.com")
                .senha("hash")
                .role(Usuario.Role.MECANICO)
                .ativo(true)
                .build();

        assertThat(u.getUsername()).isEqualTo("a@a.com");
        assertThat(u.getPassword()).isEqualTo("hash");
        assertThat(u.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly("ROLE_MECANICO");
        assertThat(u.isAccountNonExpired()).isTrue();
        assertThat(u.isCredentialsNonExpired()).isTrue();
        assertThat(u.isEnabled()).isTrue();
        assertThat(u.isAccountNonLocked()).isTrue();
    }

    @Test
    void contaBloqueada_quandoInativo() {
        Usuario u = Usuario.builder()
                .id(UUID.randomUUID())
                .email("a@a.com")
                .senha("hash")
                .role(Usuario.Role.CLIENTE)
                .ativo(false)
                .build();

        assertThat(u.isAccountNonLocked()).isFalse();
        assertThat(u.isEnabled()).isFalse();
    }
}
