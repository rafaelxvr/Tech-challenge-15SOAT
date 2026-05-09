package com.oficina.config;

import com.oficina.entity.Usuario;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private JwtService jwtService;
    private Usuario usuario;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties(SECRET, 60_000L, 120_000L);
        jwtService = new JwtService(props);
        usuario = Usuario.builder()
                .id(UUID.randomUUID())
                .email("u@test.com")
                .senha("x")
                .role(Usuario.Role.MECANICO)
                .ativo(true)
                .build();
    }

    private static SecretKey signingKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void generateToken_contemSubject() {
        String token = jwtService.generateToken(usuario);
        assertThat(jwtService.extractUsername(token)).isEqualTo("u@test.com");
    }

    @Test
    void generateToken_comClaimsExtras() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("k", "v");
        String token = jwtService.generateToken(claims, usuario);
        assertThat(jwtService.extractUsername(token)).isEqualTo("u@test.com");
    }

    @Test
    void generateRefreshToken() {
        String refresh = jwtService.generateRefreshToken(usuario);
        assertThat(refresh).isNotBlank();
        assertThat(jwtService.extractUsername(refresh)).isEqualTo("u@test.com");
    }

    @Test
    void isTokenValid_trueParaTokenCorrespondente() {
        String token = jwtService.generateToken(usuario);
        assertThat(jwtService.isTokenValid(token, usuario)).isTrue();
    }

    @Test
    void isTokenValid_falseParaOutroUsuario() {
        String token = jwtService.generateToken(usuario);
        Usuario outro = Usuario.builder()
                .id(UUID.randomUUID())
                .email("outro@test.com")
                .senha("x")
                .role(Usuario.Role.CLIENTE)
                .ativo(true)
                .build();
        assertThat(jwtService.isTokenValid(token, outro)).isFalse();
    }

    @Test
    void isTokenValid_falseParaTokenInvalido() {
        assertThat(jwtService.isTokenValid("invalid", usuario)).isFalse();
    }

    @Test
    void isTokenValid_falseParaTokenExpirado() {
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .subject("u@test.com")
                .issuedAt(new Date(now - 120_000))
                .expiration(new Date(now - 60_000))
                .signWith(signingKey())
                .compact();
        assertThat(jwtService.isTokenValid(token, usuario)).isFalse();
    }
}
