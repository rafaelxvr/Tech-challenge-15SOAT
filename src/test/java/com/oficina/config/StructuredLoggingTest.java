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
 *
 * <p>Every MDC key the snapshot telemetry feature declared in {@code logback-spring.xml}
 * ({@code event_name}, {@code error_code}, {@code failure_type}, {@code events_captured_count},
 * {@code events_exported_count}) is exercised here. {@link SnapshotSchedulerTickTest} and
 * {@link com.oficina.application.observability.SnapshotSchedulerFailureTest} read these same MDC
 * values straight off the {@code ILoggingEvent}, bypassing the encoder entirely; only this test
 * proves the encoder's whitelist actually lets each key reach the emitted JSON, so a key silently
 * dropped from {@code logback-spring.xml} still fails a test.
 */
class StructuredLoggingTest {

    @Test
    void emitsFailureSignalKeysFromMdc() throws Exception {
        String line = logThroughRealConfig(context -> {
            context.getMDCAdapter().put("event_name", "snapshot_tick_failed");
            context.getMDCAdapter().put("error_code", "SNAPSHOT_EXPORT_FAILED");
            context.getMDCAdapter().put("failure_type", "java.lang.IllegalStateException");
        });

        assertThat(line).contains("\"event_name\":\"snapshot_tick_failed\"");
        assertThat(line).contains("\"error_code\":\"SNAPSHOT_EXPORT_FAILED\"");
        assertThat(line).contains("\"failure_type\":\"java.lang.IllegalStateException\"");
    }

    @Test
    void emitsSuccessSignalCountKeysFromMdc() throws Exception {
        String line = logThroughRealConfig(context -> {
            context.getMDCAdapter().put("event_name", "snapshot_tick_completed");
            context.getMDCAdapter().put("events_captured_count", "11");
            context.getMDCAdapter().put("events_exported_count", "11");
        });

        assertThat(line).contains("\"event_name\":\"snapshot_tick_completed\"");
        assertThat(line).contains("\"events_captured_count\":\"11\"");
        assertThat(line).contains("\"events_exported_count\":\"11\"");
    }

    /**
     * Configures a fresh {@link LoggerContext} straight from the real {@code logback-spring.xml},
     * lets {@code mdcSetup} populate its MDC, emits one log line through it and returns the captured
     * stdout line. Isolated per call so the two tests never share MDC or appender state.
     */
    private String logThroughRealConfig(java.util.function.Consumer<LoggerContext> mdcSetup) throws Exception {
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
            mdcSetup.accept(context);
            context.getLogger(StructuredLoggingTest.class).warn("ignored");
        } finally {
            MDC.clear();
            context.getMDCAdapter().clear();
            context.stop();
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
