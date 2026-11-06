package com.oficina.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static net.logstash.logback.argument.StructuredArguments.keyValue;
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

    @Test
    void emitsSagaContextKeysFromMdc() throws Exception {
        String line = logThroughRealConfig(context -> {
            context.getMDCAdapter().put("event_name", "poller_falhou");
            context.getMDCAdapter().put("poller", "saga-prazos");
            context.getMDCAdapter().put("saga_id", "00000000-0000-0000-0000-000000000501");
            context.getMDCAdapter().put("os_id", "00000000-0000-0000-0000-000000000201");
        });

        assertThat(line).contains("\"poller\":\"saga-prazos\"");
        assertThat(line).contains("\"saga_id\":\"00000000-0000-0000-0000-000000000501\"");
        assertThat(line).contains("\"os_id\":\"00000000-0000-0000-0000-000000000201\"");
    }

    private static final String REDACTED = "[REDACTED]";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emitsTheFormattedMessageUnderANonLocalEnvironmentAndNeverTheSafeConstant() throws Exception {
        String line = logThroughRealConfig("staging", context -> context.getMDCAdapter().put("event_name", "evt"),
                logger -> logger.warn("sentinel message {} done", "step-7"));

        JsonNode parsed = json.readTree(line);
        assertThat(parsed.path("message").asText()).isEqualTo("sentinel message step-7 done").isNotEqualTo("safe");
        assertThat(parsed.path("service").asText()).isEqualTo("oficina-api");
        assertThat(parsed.path("environment").asText()).isEqualTo("staging");
        assertThat(parsed.path("level").asText()).isEqualTo("WARN");
        assertThat(parsed.path("event_name").asText()).isEqualTo("evt");
    }

    @Test
    void redactsEverySecretAndPiiClassFoundInsideTheMessage() throws Exception {
        String[][] cases = {
                {"Bearer fictional-token-value-123", "fictional-token-value-123"},
                {"jwt eyJfaWN0aW9u.ZmljdGlvbmFsLXBheWxvYWQ.ZmljdGlvbmFs", "ZmljdGlvbmFsLXBheWxvYWQ"},
                {"password=fictional-pass-1", "fictional-pass-1"},
                {"senha: fictional-pass-2", "fictional-pass-2"},
                {"client_secret=fictional-cs-3", "fictional-cs-3"},
                {"api_key=fictional-key-4", "fictional-key-4"},
                {"checkout_url=https://fictional.example/pay/xyz", "fictional.example"},
                {"contact fictional.user@example.test now", "fictional.user@example.test"},
                {"cpf 111.444.777-35 end", "111.444.777-35"},
                {"cpf 11144477735 end", "11144477735"},
                {"phone (11) 91234-5678 end", "91234-5678"},
                {"mp APP_USR-0000000000000000-000000-aaaa", "APP_USR-0000000000000000"},
        };
        for (String[] c : cases) {
            String line = logThroughRealConfig("staging", ctx -> { }, logger -> logger.info("start " + c[0]));
            JsonNode parsed = json.readTree(line);
            assertThat(line).as(c[0]).doesNotContain(c[1]);
            assertThat(parsed.path("message").asText()).as(c[0]).contains(REDACTED).startsWith("start ");
        }
    }

    @Test
    void redactsSensitiveStructuredFieldsByPath() throws Exception {
        String[] names = {"authorization", "cookie", "password", "senha", "secret", "segredo", "token", "api_key",
                "client_secret", "checkout_url", "cpf", "email", "phone", "telefone"};
        for (String name : names) {
            String line = logThroughRealConfig("staging", ctx -> { },
                    logger -> logger.info("structured field", keyValue(name, "fictional-value-zzz")));
            JsonNode parsed = json.readTree(line);
            assertThat(line).as(name).doesNotContain("fictional-value-zzz");
            assertThat(parsed.path(name).asText()).as(name).isEqualTo(REDACTED);
        }
    }

    @Test
    void keepsPlainMessagesWithoutSensitiveContentUntouched() throws Exception {
        String line = logThroughRealConfig("staging", ctx -> { },
                logger -> logger.info("saga resposta duplicada os=17 attempt 3 of 5"));
        assertThat(json.readTree(line).path("message").asText()).isEqualTo("saga resposta duplicada os=17 attempt 3 of 5");
    }

    /**
     * Configures a fresh {@link LoggerContext} straight from the real {@code logback-spring.xml},
     * lets {@code mdcSetup} populate its MDC, emits one log line through it and returns the captured
     * stdout line. Isolated per call so the two tests never share MDC or appender state.
     */
    private String logThroughRealConfig(java.util.function.Consumer<LoggerContext> mdcSetup) throws Exception {
        return logThroughRealConfig("local", mdcSetup, logger -> logger.warn("ignored"));
    }

    private String logThroughRealConfig(String environment, java.util.function.Consumer<LoggerContext> mdcSetup,
                                        java.util.function.Consumer<org.slf4j.Logger> emit) throws Exception {
        URL resource = getClass().getResource("/logback-spring.xml");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.putProperty("OFICINA_ENVIRONMENT", environment);
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(resource);

            // A dedicated LoggerContext has its own MDC adapter; production uses the global context.
            mdcSetup.accept(context);
            emit.accept(context.getLogger(StructuredLoggingTest.class));
        } finally {
            MDC.clear();
            context.getMDCAdapter().clear();
            context.stop();
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
