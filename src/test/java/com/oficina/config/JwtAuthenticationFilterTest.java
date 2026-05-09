package com.oficina.config;

import com.oficina.entity.Usuario;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    JwtService jwtService;

    @Mock
    org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    @Mock
    FilterChain filterChain;

    JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, userDetailsService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void semHeader_continuaCadeia() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, filterChain);

        verify(filterChain).doFilter(req, res);
        verify(jwtService, never()).extractUsername(anyString());
    }

    @Test
    void headerSemBearer_continuaCadeia() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Basic xxx");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, filterChain);

        verify(jwtService, never()).extractUsername(anyString());
        verify(filterChain).doFilter(req, res);
    }

    @Test
    void tokenValido_preencheSecurityContext() throws ServletException, IOException {
        Usuario usuario = Usuario.builder()
                .id(UUID.randomUUID())
                .email("u@test.com")
                .senha("s")
                .role(Usuario.Role.ADMIN)
                .ativo(true)
                .build();

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer token.jwt.here");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(jwtService.extractUsername("token.jwt.here")).thenReturn("u@test.com");
        when(userDetailsService.loadUserByUsername("u@test.com")).thenReturn(usuario);
        when(jwtService.isTokenValid("token.jwt.here", usuario)).thenReturn(true);

        filter.doFilterInternal(req, res, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(usuario);
        verify(filterChain).doFilter(req, res);
    }

    @Test
    void falhaNoJwt_continuaSemAutenticar() throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse res = new MockHttpServletResponse();

        when(jwtService.extractUsername("bad")).thenThrow(new RuntimeException("parse"));

        filter.doFilterInternal(req, res, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(req, res);
    }
}
