package com.oficina.adapter.out.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.assertThat;

class NewRelicOrderTelemetryTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(NewRelicOrderTelemetry.class);
    private final ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ListAppender<>();

    @AfterEach void cleanup() { logger.detachAppender(logs); MDC.clear(); }

    @Test void emits_only_bounded_command_outcome_and_restores_request_diagnostics() {
        logs.start(); logger.addAppender(logs); MDC.put("event_name", "request"); MDC.put("diagnostic_id", "request-id");
        var telemetry = new NewRelicOrderTelemetry();
        telemetry.commandCompleted("order_decision", "accepted");
        telemetry.commandCompleted("unexpected", "private@example.test");
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getMDCPropertyMap()).containsEntry("event_name", "order_command_completed")
                .containsEntry("diagnostic_id", "order_decision:accepted");
        assertThat(MDC.get("event_name")).isEqualTo("request");
        assertThat(MDC.get("diagnostic_id")).isEqualTo("request-id");
    }
}
