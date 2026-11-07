package com.oficina.config;

import com.oficina.support.TokenFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile {@code aws} runs without an SMTP server: the aggregate health must stay UP, so the mail contributor
 * must be disabled by the profile itself (no MAIL_HOST / management.health.mail.enabled supplied here).
 * The Learner Lab guard has its own test and is mocked to keep this one independent of the runner's AWS files.
 */
@SpringBootTest(properties = {"oficina.mail.enabled=false", "oficina.historico.zona-compatibilidade=UTC"})
@AutoConfigureMockMvc
@ActiveProfiles("aws")
class AwsMailHealthProfileRuntimeTest {
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

    @MockBean AwsLearnerLabGuard guard;
    @Autowired MockMvc mvc;
    @Autowired Environment environment;
    @Autowired HealthContributorRegistry healthContributors;

    @Test void aggregate_health_is_up_without_smtp_because_the_profile_disables_mail_health() throws Exception {
        assertThat(environment.getProperty("management.health.mail.enabled", Boolean.class)).isFalse();
        assertThat(healthContributors.getContributor("mail")).isNull();
        mvc.perform(get("/api/actuator/health").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
