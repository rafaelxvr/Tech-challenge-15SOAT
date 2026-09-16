package com.oficina.config;

import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the deployed Actuator health groups through the servlet endpoint. */
@SpringBootTest(properties = {"oficina.mail.enabled=false", "management.health.mail.enabled=false",
        "oficina.historico.zona-compatibilidade=UTC"})
@AutoConfigureMockMvc
class ActuatorHealthGroupsRuntimeTest {
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

    @Autowired MockMvc mvc;
    @Autowired HealthContributorRegistry healthContributors;
    private final AtomicInteger databaseCalls = new AtomicInteger();
    private HealthContributor originalDatabase;

    @BeforeEach void databaseIsUnavailable() {
        originalDatabase = healthContributors.unregisterContributor("db");
        assertThat(originalDatabase).isNotNull();
        HealthIndicator unavailable = () -> {
            databaseCalls.incrementAndGet();
            return Health.down().withDetail("error", "synthetic database down").build();
        };
        healthContributors.registerContributor("db", unavailable);
    }

    @AfterEach void restoreDatabaseContributor() {
        healthContributors.unregisterContributor("db");
        healthContributors.registerContributor("db", originalDatabase);
    }

    @Test void failing_database_makes_configured_readiness_down_while_liveness_remains_up() throws Exception {
        mvc.perform(get("/api/actuator/health/readiness").contextPath("/api"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"));
        assertThat(databaseCalls).hasValue(1);

        mvc.perform(get("/api/actuator/health/liveness").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(databaseCalls).hasValue(1);
    }
}
