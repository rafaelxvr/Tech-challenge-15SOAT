package com.oficina.security;

import com.oficina.config.JwtService;
import com.oficina.entity.Cliente;
import com.oficina.entity.Usuario;
import com.oficina.repository.ClienteRepository;
import com.oficina.repository.UsuarioRepository;
import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenTrustTest {
    private final TokenFixtures tokenFixtures = new TokenFixtures();
    private final ClienteRepository clientes = mock(ClienteRepository.class);
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final Cliente cliente = mock(Cliente.class);
    private StaffTokenValidator staffValidator;
    private CustomerTokenValidator customerValidator;
    private Usuario staff;

    @BeforeEach void setup() {
        staff = Usuario.builder().id(TokenFixtures.STAFF_ID).email(TokenFixtures.STAFF_EMAIL)
                .role(Usuario.Role.ADMIN).ativo(true).build();
        when(usuarios.findByEmail(TokenFixtures.STAFF_EMAIL)).thenReturn(Optional.of(staff));
        when(clientes.findById(TokenFixtures.CUSTOMER_ID)).thenReturn(Optional.of(cliente));
        when(cliente.isAtivo()).thenReturn(true);
        when(cliente.getVersaoIdentidade()).thenReturn(1L);
        staffValidator = new StaffTokenValidator(new JwtService(tokenFixtures.properties(), TokenFixtures.CLOCK), usuarios);
        customerValidator = new CustomerTokenValidator(tokenFixtures.properties(), clientes, TokenFixtures.CLOCK);
    }

    @Test void refreshTokenCannotAuthenticateAResource() {
        String refresh = tokenFixtures.staff("refresh", "staging");
        assertThatThrownBy(() -> staffValidator.validar(refresh)).isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(usuarios);
    }

    @Test void trustedActorsRemainIsolated() {
        var customer = customerValidator.validar(tokenFixtures.customer(TokenFixtures.CUSTOMER_ID, 1, "staging", TokenFixtures.NOW.plusSeconds(30)));
        assertThat(customer.tipo()).isEqualTo(TipoPrincipal.CUSTOMER);
        assertThat(customer.id()).isEqualTo(TokenFixtures.CUSTOMER_ID);
        assertThat(customer.versaoIdentidade()).isEqualTo(1);
        assertThat(customer.permissoes()).containsExactlyInAnyOrder("SCOPE_orders:read:self", "SCOPE_orders:decide:self");
        var identity = staffValidator.validar(tokenFixtures.staff("access", "staging"));
        assertThat(identity.tipo()).isEqualTo(TipoPrincipal.STAFF);
        assertThat(identity.id()).isEqualTo(TokenFixtures.STAFF_ID);
        assertThat(identity.permissoes()).containsExactly("ROLE_ADMIN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "issuerMissing", "audience", "audienceMissing", "extraAudience", "expired", "expirationMissing", "issuedAtMissing", "futureIssuedAt", "futureNotBefore",
            "purpose", "purposeMissing", "principal", "principalMissing", "subject", "kidMissing", "kidUnknown", "jku", "x5u", "jwk", "x5c", "tampered", "environment"})
    void rejectsInvalidTrustForBothPrincipals(String attack) {
        for (boolean customer : List.of(true, false)) {
            var claims = tokenFixtures.claims(customer ? "customerAccess" : "staffAccess", "staging");
            Map<String, Object> header = new HashMap<>(Map.of("kid", customer ? TokenFixtures.CUSTOMER_KID : TokenFixtures.STAFF_KID));
            switch (attack) {
                case "issuer" -> claims.put("iss", "attacker");
                case "issuerMissing" -> claims.remove("iss");
                case "audience" -> claims.put("aud", "other-api");
                case "audienceMissing" -> claims.remove("aud");
                case "extraAudience" -> claims.put("aud", List.of("oficina-staging-api", "other-api"));
                case "expired" -> claims.put("exp", Date.from(TokenFixtures.NOW));
                case "expirationMissing" -> claims.remove("exp");
                case "issuedAtMissing" -> claims.remove("iat");
                case "futureIssuedAt" -> claims.put("iat", Date.from(TokenFixtures.NOW.plusSeconds(5)));
                case "futureNotBefore" -> claims.put("nbf", Date.from(TokenFixtures.NOW.plusSeconds(5)));
                case "purpose" -> claims.put("token_use", "refresh");
                case "purposeMissing" -> claims.remove("token_use");
                case "principal" -> claims.put("principal_type", customer ? "staff" : "customer");
                case "principalMissing" -> claims.remove("principal_type");
                case "subject" -> claims.remove("sub");
                case "kidMissing" -> header.remove("kid");
                case "kidUnknown" -> header.put("kid", "unknown");
                case "jku", "x5u" -> header.put(attack, "https://attacker.invalid/keys");
                case "jwk" -> header.put("jwk", Map.of("kty", "oct", "k", "YXNkZg"));
                case "x5c" -> header.put("x5c", List.of("YWJj"));
                case "environment" -> claims = tokenFixtures.claims(customer ? "customerAccess" : "staffAccess", "production");
                default -> { }
            }
            String signed = customer ? tokenFixtures.signCustomer(claims, header) : tokenFixtures.signStaff(claims, header);
            if (attack.equals("tampered")) {
                String[] parts = signed.split("\\.");
                parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
                signed = String.join(".", parts);
            }
            String token = signed;
            ValidadorToken validator = customer ? customerValidator : staffValidator;
            assertThatThrownBy(() -> validator.validar(token)).as("%s / customer=%s", attack, customer)
                    .isInstanceOf(BadCredentialsException.class).hasMessage("Invalid credentials");
        }
        verifyNoInteractions(clientes, usuarios);
    }

    @ParameterizedTest @ValueSource(strings = {"missingVersion", "staleVersion", "stringVersion", "fractionalVersion", "zeroVersion",
            "invalidSubject", "staffRoles", "unknownScope", "missingScopes"})
    void rejectsInvalidCustomerIdentity(String attack) {
        var claims = tokenFixtures.claims("customerAccess", "staging");
        switch (attack) {
            case "missingVersion" -> claims.remove("identity_version");
            case "staleVersion" -> claims.put("identity_version", 2);
            case "stringVersion" -> claims.put("identity_version", "1");
            case "fractionalVersion" -> claims.put("identity_version", 1.5);
            case "zeroVersion" -> claims.put("identity_version", 0);
            case "invalidSubject" -> claims.put("sub", TokenFixtures.STAFF_EMAIL);
            case "staffRoles" -> claims.put("roles", List.of("ADMIN"));
            case "unknownScope" -> claims.put("scopes", List.of("admin:all"));
            case "missingScopes" -> claims.remove("scopes");
        }
        String token = tokenFixtures.signCustomer(claims, Map.of("kid", TokenFixtures.CUSTOMER_KID));
        assertThatThrownBy(() -> customerValidator.validar(token)).isInstanceOf(BadCredentialsException.class);
    }

    @Test void rechecksCurrentCustomerStatusAndVersionEveryTime() {
        String token = tokenFixtures.customer(TokenFixtures.CUSTOMER_ID, 1, "staging", TokenFixtures.NOW.plusSeconds(60));
        customerValidator.validar(token);
        when(cliente.isAtivo()).thenReturn(false);
        assertThatThrownBy(() -> customerValidator.validar(token)).isInstanceOf(BadCredentialsException.class);
        when(cliente.isAtivo()).thenReturn(true);
        when(cliente.getVersaoIdentidade()).thenReturn(2L);
        assertThatThrownBy(() -> customerValidator.validar(token)).isInstanceOf(BadCredentialsException.class);
        when(clientes.findById(TokenFixtures.CUSTOMER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> customerValidator.validar(token)).isInstanceOf(BadCredentialsException.class);
        verify(clientes, times(4)).findById(TokenFixtures.CUSTOMER_ID);
    }

    @Test void rechecksCurrentStaffRolesAndStatusEveryTime() {
        String token = tokenFixtures.staff("access", "staging");
        staffValidator.validar(token);
        staff.setRole(Usuario.Role.MECANICO);
        assertThat(staffValidator.validar(token).permissoes()).containsExactly("ROLE_MECANICO");
        staff.setAtivo(false);
        assertThatThrownBy(() -> staffValidator.validar(token)).isInstanceOf(BadCredentialsException.class);
        staff.setAtivo(true);
        staff.setRole(Usuario.Role.CLIENTE);
        assertThatThrownBy(() -> staffValidator.validar(token)).isInstanceOf(BadCredentialsException.class);
        when(usuarios.findByEmail(TokenFixtures.STAFF_EMAIL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> staffValidator.validar(token)).isInstanceOf(BadCredentialsException.class);
        verify(usuarios, times(5)).findByEmail(TokenFixtures.STAFF_EMAIL);
    }

    @ParameterizedTest @ValueSource(strings = {"scopes", "identity_version", "both"})
    void rejectsExternallySignedStaffTokensWithCustomerOnlyClaimsBeforeLookup(String injectedClaim) {
        var claims = tokenFixtures.claims("staffAccess", "staging");
        if (!injectedClaim.equals("identity_version")) claims.put("scopes", List.of("orders:read:self"));
        if (!injectedClaim.equals("scopes")) claims.put("identity_version", 1);
        // Sign directly with the trusted fixture key; bypass JwtService's reserved-claim removal.
        String token = tokenFixtures.signStaff(claims, Map.of("kid", TokenFixtures.STAFF_KID));
        assertThatThrownBy(() -> staffValidator.validar(token))
                .isInstanceOf(BadCredentialsException.class).hasMessage("Invalid credentials");
        verifyNoInteractions(usuarios, clientes);
    }

    @Test void rejectsAlgorithmConfusionAndCrossTrust() {
        assertThatThrownBy(() -> customerValidator.validar(tokenFixtures.wrongAlgorithm())).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> staffValidator.validar(tokenFixtures.staffWrongAlgorithm())).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> staffValidator.validar(tokenFixtures.customer(TokenFixtures.CUSTOMER_ID, 1, "staging", TokenFixtures.NOW.plusSeconds(60))))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> customerValidator.validar(tokenFixtures.staff("access", "staging"))).isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(clientes, usuarios);
    }

    @Test void productionValidatorsRejectStagingTokens() {
        var production = new CustomerTokenValidator(tokenFixtures.properties("production"), clientes, TokenFixtures.CLOCK);
        var productionStaff = new StaffTokenValidator(new JwtService(tokenFixtures.properties("production"), TokenFixtures.CLOCK), usuarios);
        assertThatThrownBy(() -> production.validar(tokenFixtures.customer(TokenFixtures.CUSTOMER_ID, 1, "staging", TokenFixtures.NOW.plusSeconds(30))))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> productionStaff.validar(tokenFixtures.staff("access", "staging"))).isInstanceOf(BadCredentialsException.class);
    }
}
