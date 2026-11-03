package com.oficina.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void correlation_is_returned_and_cleared_after_the_request() throws Exception {
        var request = new MockHttpServletRequest(); request.addHeader("X-Correlation-Id", "00000000-0000-0000-0000-000000000123");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, ignored) -> assertThat(MDC.get("correlation_id")).isEqualTo("00000000-0000-0000-0000-000000000123");
        new CorrelationFilter().doFilter(request, response, chain);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("00000000-0000-0000-0000-000000000123");
        assertThat(MDC.get("correlation_id")).isNull(); assertThat(MDC.get("traceparent")).isNull();
    }

    @Test void gateway_request_id_is_read_from_the_real_api_gateway_header_and_cleared_after_the_request() throws Exception {
        // The deployed HTTP API integration injects "$context.requestId" under this header name, not x-amzn-requestid.
        var request = new MockHttpServletRequest(); request.addHeader("X-Gateway-Request-Id", "EEFW1hBXoAMESEQ=");
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, ignored) -> assertThat(MDC.get("api_gateway_request_id")).isEqualTo("EEFW1hBXoAMESEQ=");
        new CorrelationFilter().doFilter(request, response, chain);
        assertThat(MDC.get("api_gateway_request_id")).isNull();
    }

    @Test void a_hostile_gateway_request_id_is_rejected_and_never_reaches_mdc() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Gateway-Request-Id", "\"} malicious \"injected_field\":\"x");
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, ignored) -> assertThat(MDC.get("api_gateway_request_id")).isNull();
        new CorrelationFilter().doFilter(request, response, chain);
        assertThat(MDC.get("api_gateway_request_id")).isNull();
    }

    @Test void configured_encoder_is_json_and_cannot_include_untrusted_messages_or_exceptions() throws Exception {
        URL resource = getClass().getResource("/logback-spring.xml");
        String config = Files.readString(java.nio.file.Path.of(resource.toURI()));
        String bearer = "Bearer eyJhbGciOiJIUzI1NiJ9.private";
        String cpf = "39053344705";
        String email = "private@example.test";
        assertThat(config).contains("LoggingEventCompositeJsonEncoder", "<timeZone>UTC</timeZone>")
                .doesNotContain("%msg", "%ex", "%throwable", "stackTrace");
        ByteArrayOutputStream captured = new ByteArrayOutputStream(); PrintStream original = System.out;
        LoggerContext context = new LoggerContext(); context.setMDCAdapter(new LogbackMDCAdapter());
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            JoranConfigurator configurator = new JoranConfigurator(); configurator.setContext(context);
            configurator.doConfigure(resource);
            // A dedicated LoggerContext has its own MDC adapter; production uses the global context.
            context.getMDCAdapter().put("correlation_id", "correlation-123");
            context.getLogger("observability.contract").info("{} {} {} {} {} {}", bearer, cpf, email, "plate-123", "select *", "sensitive exception");
        } finally { MDC.clear(); context.getMDCAdapter().clear(); context.stop(); System.setOut(original); }
        String line = captured.toString(StandardCharsets.UTF_8).trim();
        var parsed = json.readTree(line);
        assertThat(parsed.path("correlation_id").asText()).isEqualTo("correlation-123");
        assertThat(line).doesNotContain(bearer, cpf, email, "plate-123", "select *", "sensitive exception");
    }
}
