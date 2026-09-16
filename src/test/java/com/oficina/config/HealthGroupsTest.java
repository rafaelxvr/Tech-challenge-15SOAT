package com.oficina.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.availability.LivenessStateHealthIndicator;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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

    @Test void database_outage_makes_runtime_readiness_component_down_while_liveness_stays_up() throws Exception {
        DataSource unavailable = mock(DataSource.class);
        when(unavailable.getConnection()).thenThrow(new SQLException("synthetic database down"));
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        when(availability.getLivenessState()).thenReturn(LivenessState.CORRECT);
        assertThat(new DataSourceHealthIndicator(unavailable).health().getStatus().getCode()).isEqualTo("DOWN");
        assertThat(new LivenessStateHealthIndicator(availability).health().getStatus().getCode()).isEqualTo("UP");
    }
}
