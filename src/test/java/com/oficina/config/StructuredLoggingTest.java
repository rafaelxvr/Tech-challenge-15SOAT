package com.oficina.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The appender is exercised through a dedicated {@link LoggerContext} configured straight from the
 * real {@code logback-spring.xml}, the same approach {@code ObservabilityContractTest} uses. A plain
 * {@code LoggerFactory.getILoggerFactory()} lookup is unreliable here: {@code logback-spring.xml} is
 * only auto-loaded by Spring Boot's logging system during context startup, so a root logger obtained
 * outside a Spring context (as in a lone unit test run) never has the {@code JSON_CONSOLE} appender
 * attached.
 */
class StructuredLoggingTest {

    @Test
    void emitsEventNameAndErrorCodeFromMdc() throws Exception {
        URL resource = getClass().getResource("/logback-spring.xml");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(resource);

            // A dedicated LoggerContext has its own MDC adapter; production uses the global context.
            context.getMDCAdapter().put("event_name", "snapshot_export_failed");
            context.getMDCAdapter().put("error_code", "SNAPSHOT_TRANSPORT_UNAVAILABLE");
            context.getLogger(StructuredLoggingTest.class).warn("ignored");
        } finally {
            MDC.clear();
            context.getMDCAdapter().clear();
            context.stop();
            System.setOut(original);
        }

        String line = captured.toString(StandardCharsets.UTF_8);
        assertThat(line).contains("\"event_name\":\"snapshot_export_failed\"");
        assertThat(line).contains("\"error_code\":\"SNAPSHOT_TRANSPORT_UNAVAILABLE\"");
    }
}
