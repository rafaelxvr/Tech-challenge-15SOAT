package com.oficina.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
