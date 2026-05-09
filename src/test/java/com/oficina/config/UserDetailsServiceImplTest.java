package com.oficina.config;

import com.oficina.entity.Usuario;
import com.oficina.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock
    UsuarioRepository usuarioRepository;

    @InjectMocks
    UserDetailsServiceImpl userDetailsService;

    @Test
    void loadUserByUsername_encontraUsuario() {
        Usuario u = Usuario.builder()
                .id(UUID.randomUUID())
                .email("a@a.com")
                .senha("s")
                .role(Usuario.Role.ADMIN)
                .ativo(true)
                .build();
        when(usuarioRepository.findByEmail("a@a.com")).thenReturn(Optional.of(u));

        assertThat(userDetailsService.loadUserByUsername("a@a.com")).isSameAs(u);
    }

    @Test
    void loadUserByUsername_lancaQuandoNaoExiste() {
        when(usuarioRepository.findByEmail("x@x.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("x@x.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("x@x.com");
    }
}
