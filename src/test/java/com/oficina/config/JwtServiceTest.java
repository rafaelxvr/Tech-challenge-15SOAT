package com.oficina.config;

import com.oficina.entity.Usuario;
import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {
    private final TokenFixtures fixtures = new TokenFixtures();
    private JwtService jwtService;
    private Usuario usuario;

    @BeforeEach void setUp() {
        jwtService = new JwtService(fixtures.properties(), TokenFixtures.CLOCK);
        usuario = Usuario.builder().id(TokenFixtures.STAFF_ID).email(TokenFixtures.STAFF_EMAIL)
                .senha("x").role(Usuario.Role.MECANICO).ativo(true).build();
    }

    @Test void generatedAccessHasPinnedContractAndLifetime() {
        String token = jwtService.generateToken(usuario);
        var parsed = fixtures.readStaff(token);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo(TokenFixtures.STAFF_KID);
        assertThat(jwtService.extractUsername(token)).isEqualTo(usuario.getEmail());
        assertThat(parsed.getPayload().getIssuer()).isEqualTo("oficina-staging-staff");
        assertThat(parsed.getPayload().getAudience()).containsExactly("oficina-staging-api");
        assertThat(parsed.getPayload()).containsEntry("principal_type", "staff").containsEntry("token_use", "access")
                .containsEntry("roles", List.of("MECANICO"));
        assertThat(parsed.getPayload().getExpiration()).isEqualTo(Date.from(TokenFixtures.NOW.plusSeconds(60)));
        assertThat(jwtService.isTokenValid(token, usuario)).isTrue();
    }

    @Test void reservedExtraClaimsCannotChangeTrustPurposeOrPermissions() {
        Map<String, Object> extras = new HashMap<>();
        extras.put("iss", "attacker"); extras.put("aud", "other-api"); extras.put("sub", "other-user");
        extras.put("token_use", "refresh"); extras.put("principal_type", "customer");
        extras.put("roles", List.of("ADMIN")); extras.put("scopes", List.of("admin:all"));
        extras.put("identity_version", 1); extras.put("iat", 0); extras.put("exp", 1); extras.put("nbf", 9999999999L);
        extras.put("jti", "attacker-id"); extras.put("k", "v");
        String token = jwtService.generateToken(extras, usuario);
        var claims = jwtService.validarAccessToken(token);
        assertThat(claims).containsEntry("token_use", "access").containsEntry("principal_type", "staff")
                .containsEntry("roles", List.of("MECANICO")).containsEntry("k", "v")
                .doesNotContainKeys("scopes", "identity_version", "nbf", "jti");
        assertThat(claims.getIssuer()).isEqualTo("oficina-staging-staff");
        assertThat(claims.getSubject()).isEqualTo(usuario.getEmail());
        assertThat(claims.getExpiration()).isEqualTo(Date.from(TokenFixtures.NOW.plusSeconds(60)));
        assertThat(extras).containsEntry("token_use", "refresh");
    }

    @Test void refreshIsSignedSeparatelyAndCannotBeUsedForAccessOrExtraction() {
        String refresh = jwtService.generateRefreshToken(usuario);
        var claims = fixtures.readStaff(refresh).getPayload();
        assertThat(claims).containsEntry("token_use", "refresh").containsEntry("principal_type", "staff");
        assertThat(claims.getExpiration()).isEqualTo(Date.from(TokenFixtures.NOW.plusSeconds(120)));
        assertThat(jwtService.isTokenValid(refresh, usuario)).isFalse();
        assertThatThrownBy(() -> jwtService.extractUsername(refresh)).isInstanceOf(BadCredentialsException.class);
    }

    @Test void rejectsWrongUserInactiveAndMalformedTokens() {
        String token = jwtService.generateToken(usuario);
        usuario.setEmail("other@example.invalid");
        assertThat(jwtService.isTokenValid(token, usuario)).isFalse();
        assertThat(jwtService.isTokenValid("invalid", usuario)).isFalse();
        usuario.setAtivo(false);
        assertThat(jwtService.isTokenValid(token, usuario)).isFalse();
        assertThatThrownBy(() -> jwtService.generateToken(usuario)).isInstanceOf(BadCredentialsException.class);
    }

    @Test void legacyClienteRoleCannotAcquireStaffTokens() {
        usuario.setRole(Usuario.Role.CLIENTE);
        assertThatThrownBy(() -> jwtService.generateToken(usuario)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> jwtService.generateRefreshToken(usuario)).isInstanceOf(BadCredentialsException.class);
    }
}
