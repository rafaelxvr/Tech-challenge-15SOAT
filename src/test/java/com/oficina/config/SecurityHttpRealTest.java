package com.oficina.config;

import com.oficina.entity.Usuario;
import com.oficina.repository.UsuarioRepository;
import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP real (servlet container): MockMvc não redespacha para /error, então só aqui o 403 vindo de
 * sendError() seria reautorizado sem credencial e viraria 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"oficina.mail.enabled=false",
        "management.health.mail.enabled=false", "oficina.historico.zona-compatibilidade=UTC"})
@Import(SecurityHttpRealTest.TestClock.class)
class SecurityHttpRealTest {
    private static final TokenFixtures TOKENS = new TokenFixtures();
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static { POSTGRES.start(); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        var jwt = TOKENS.properties();
        properties.add("security.jwt.secret", jwt::secret);
        properties.add("security.jwt.staff.issuer", jwt.staff()::issuer);
        properties.add("security.jwt.staff.audience", jwt.staff()::audience);
        properties.add("security.jwt.staff.key-id", jwt.staff()::keyId);
        properties.add("security.jwt.customer.issuer", jwt.customer()::issuer);
        properties.add("security.jwt.customer.audience", jwt.customer()::audience);
        properties.add("security.jwt.customer.public-keys." + TokenFixtures.CUSTOMER_KID,
                () -> jwt.customer().publicKeys().get(TokenFixtures.CUSTOMER_KID));
    }

    @TestConfiguration static class TestClock {
        @Bean @Primary Clock fixtureClock() { return TokenFixtures.CLOCK; }
    }

    private static final String ROTA_ADMIN = "/api/admin/metricas/tempo-execucao-servicos";

    @LocalServerPort int port;
    @Autowired UsuarioRepository usuarios;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void mecanicoExisteNoBanco() {
        usuarios.findByEmail(TokenFixtures.STAFF_EMAIL).orElseGet(() -> usuarios.save(Usuario.builder()
                .email(TokenFixtures.STAFF_EMAIL).senha("fixture-only-unused").ativo(true).role(Usuario.Role.MECANICO).build()));
    }

    private HttpResponse<String> get(String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + ROTA_ADMIN)).GET();
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void mecanicoEmRotaSoAdminRecebe403() throws Exception {
        assertThat(get(TOKENS.staff("access", "staging")).statusCode()).isEqualTo(403);
    }

    @Test void semCredencialContinuaRecebendo401() throws Exception {
        var resposta = get(null);
        assertThat(resposta.statusCode()).isEqualTo(401);
        assertThat(resposta.body()).contains("UNAUTHORIZED");
    }

    @Test void tokenInvalidoContinuaRecebendo401() throws Exception {
        assertThat(get("lixo").statusCode()).isEqualTo(401);
    }
}
