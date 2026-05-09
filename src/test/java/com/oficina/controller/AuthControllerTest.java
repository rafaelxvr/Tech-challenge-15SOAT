package com.oficina.controller;

import com.oficina.config.JwtService;
import com.oficina.entity.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    AuthenticationManager authenticationManager;

    @Mock
    JwtService jwtService;

    @Mock
    Authentication authentication;

    AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(authenticationManager, jwtService);
    }

    @Test
    void login_retornaTokens() {
        Usuario usuario = Usuario.builder()
                .id(UUID.randomUUID())
                .email("admin@oficina.com")
                .senha("hash")
                .role(Usuario.Role.ADMIN)
                .ativo(true)
                .build();

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(usuario);
        when(jwtService.generateToken(usuario)).thenReturn("access");
        when(jwtService.generateRefreshToken(usuario)).thenReturn("refresh");

        var req = new AuthController.LoginRequest("admin@oficina.com", "secret");
        var res = controller.login(req);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().accessToken()).isEqualTo("access");
        assertThat(res.getBody().refreshToken()).isEqualTo("refresh");
        assertThat(res.getBody().tokenType()).isEqualTo("Bearer");
        assertThat(res.getBody().role()).isEqualTo("ADMIN");
        verify(jwtService).generateToken(usuario);
        verify(jwtService).generateRefreshToken(usuario);
    }
}
