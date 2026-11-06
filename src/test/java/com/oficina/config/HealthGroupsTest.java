package com.oficina.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class HealthGroupsTest {
    @Test void readiness_depends_on_database_but_liveness_does_not() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("show-details: never", "probes:", "enabled: true", "readinessState,db", "livenessState")
                .doesNotContain("readinessState,db,mail", "readinessState,db,sqs", "readinessState,db,telemetry");
    }
    @Test void healthcheck_uses_liveness_with_reviewed_timing() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        assertThat(dockerfile).contains("--interval=10s", "--start-period=120s", "--retries=3", "/actuator/health/liveness");
    }
    @Test void image_delivers_a_checksum_pinned_agent_without_embedding_credentials() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        assertThat(dockerfile)
                .contains("NEW_RELIC_JAVA_AGENT_VERSION=9.4.0", "NEW_RELIC_JAVA_AGENT_SHA256=1f8f42d25e6565a1a7088543deeeefacdd2b028761ac94743dd1715cf7ddf5f4")
                .contains("newrelic-agent:${NEW_RELIC_JAVA_AGENT_VERSION}:jar", "sha256sum -c -")
                .contains("COPY --from=newrelic-agent --chown=10001:10001 /opt/newrelic/newrelic.jar /app/newrelic/newrelic.jar")
                .contains("export JAVA_TOOL_OPTIONS=\\\"-javaagent:/app/newrelic/newrelic.jar ${JAVA_TOOL_OPTIONS:-}\\\"")
                .doesNotContain("ENV JAVA_TOOL_OPTIONS")
                .contains("NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED=false")
                .contains("OFICINA_ENVIRONMENT=\\\"${DEPLOYMENT_ENVIRONMENT:?DEPLOYMENT_ENVIRONMENT is required}\\\"", "NEW_RELIC_LABELS=\\\"environment:${DEPLOYMENT_ENVIRONMENT}\\\"")
                .doesNotContain("NEW_RELIC_LICENSE_KEY=", "NEW_RELIC_API_KEY=");
    }
    @Test void compose_supplies_the_required_deployment_environment() throws Exception {
        assertThat(Files.readString(Path.of("docker-compose.yml"))).contains("DEPLOYMENT_ENVIRONMENT: local");
    }
    @Test void aws_profile_turns_off_the_mail_health_contributor_because_it_has_no_smtp() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("aws", new ClassPathResource("application-aws.yml"));
        assertThat(sources).anySatisfy(source ->
                assertThat(source.getProperty("management.health.mail.enabled")).isEqualTo(false));
    }
}
