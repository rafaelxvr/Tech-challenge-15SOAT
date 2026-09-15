package com.oficina.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.entity.Cliente;
import com.oficina.entity.Usuario;
import com.oficina.repository.ClienteRepository;
import com.oficina.repository.UsuarioRepository;
import com.oficina.security.*;
import com.oficina.support.TokenFixtures;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
            new ch.qos.logback.core.read.ListAppender<>();
    private final TokenFixtures fixtures = new TokenFixtures();
    private final FilterChain chain = mock(FilterChain.class);
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final ClienteRepository clientes = mock(ClienteRepository.class);
    private JwtAuthenticationFilter filter;

    @BeforeEach void setup() {
        logs.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.oficina")).addAppender(logs);
        SecurityContextHolder.clearContext();
        var service = new JwtService(fixtures.properties(), TokenFixtures.CLOCK);
        filter = new JwtAuthenticationFilter(new CustomerTokenValidator(fixtures.properties(), clientes, TokenFixtures.CLOCK),
                new StaffTokenValidator(service, usuarios), new ObjectMapper());
        when(usuarios.findByEmail(TokenFixtures.STAFF_EMAIL)).thenReturn(Optional.of(Usuario.builder()
                .id(TokenFixtures.STAFF_ID).email(TokenFixtures.STAFF_EMAIL).role(Usuario.Role.ADMIN).ativo(true).build()));
        Cliente cliente = mock(Cliente.class);
        when(cliente.isAtivo()).thenReturn(true);
        when(cliente.getVersaoIdentidade()).thenReturn(1L);
        when(clientes.findById(TokenFixtures.CUSTOMER_ID)).thenReturn(Optional.of(cliente));
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.oficina")).detachAppender(logs);
        logs.stop();
    }

    @Test void absentHeaderContinuesWithoutAuthentication() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(usuarios, clientes);
    }

    @ParameterizedTest @ValueSource(strings = {"staff", "customer"})
    void validTokenInstallsTypedPrincipalAndExplicitAuthorities(String type) throws Exception {
        String token = type.equals("staff") ? fixtures.staff("access", "staging")
                : fixtures.customer(TokenFixtures.CUSTOMER_ID, 1, "staging", TokenFixtures.NOW.plusSeconds(60));
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        var identity = (IdentidadeAutenticada) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(identity.tipo()).isEqualTo(type.equals("staff") ? TipoPrincipal.STAFF : TipoPrincipal.CUSTOMER);
        assertThat(new AtorContexto().atual().id()).isEqualTo(identity.id());
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrderElementsOf(identity.permissoes());
        if (type.equals("customer")) assertThat(SecurityUtils.usuarioAutenticadoId()).isEmpty();
        else assertThat(SecurityUtils.usuarioAutenticadoId()).contains(TokenFixtures.STAFF_ID);
        verify(chain).doFilter(request, response);
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "basic", "refresh", "wrongAlgorithm", "forgedDiscriminator",
            "customerSignedStaff", "unknownType", "huge", "empty", "databaseFailure"})
    void invalidTokenClearsExistingContextAndReturnsSafe401(String attack) throws Exception {
        String token = switch (attack) {
            case "refresh" -> fixtures.staff("refresh", "staging");
            case "wrongAlgorithm" -> fixtures.wrongAlgorithm();
            case "customerSignedStaff" -> {
                var claims = fixtures.claims("customerAccess", "staging"); claims.put("principal_type", "staff");
                claims.put("iss", "oficina-staging-staff"); claims.put("sub", TokenFixtures.STAFF_EMAIL);
                yield fixtures.signCustomer(claims, Map.of("kid", TokenFixtures.STAFF_KID));
            }
            case "forgedDiscriminator" -> {
                String[] parts = fixtures.staff("access", "staging").split("\\.");
                String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).replace("staff", "customer");
                parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8));
                yield String.join(".", parts);
            }
            case "unknownType" -> fixtures.signStaff(Map.of("principal_type", "system"), Map.of("kid", TokenFixtures.STAFF_KID));
            case "huge" -> "x".repeat(16385);
            case "empty" -> "";
            case "databaseFailure" -> {
                when(usuarios.findByEmail(TokenFixtures.STAFF_EMAIL)).thenThrow(new RuntimeException("sensitive-token-marker"));
                yield fixtures.staff("access", "staging");
            }
            default -> "sensitive-token-marker";
        };
        var old = new IdentidadeAutenticada(TipoPrincipal.STAFF, TokenFixtures.STAFF_ID, Set.of("ROLE_ADMIN"), 0);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(old, null, java.util.List.of()));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", (attack.equals("basic") ? "Basic " : "Bearer ") + token);
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"UNAUTHORIZED\"}");
        assertThat(logs.list).isEmpty();
        verifyNoInteractions(chain);
        assertThatThrownBy(() -> new AtorContexto().atual()).isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
    }
}
