package com.oficina.adapter.out.observability;

import com.oficina.application.observability.OrderTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import java.util.Set;

/** Safe adapter boundary. The Java agent may consume the counter log but gets no request data or exception text. */
@Component
public final class NewRelicOrderTelemetry implements OrderTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(NewRelicOrderTelemetry.class);
    private static final Set<String> OPERATIONS = Set.of("order_create", "order_transition", "order_decision");
    private static final Set<String> OUTCOMES = Set.of("accepted", "business-rejected", "conflict", "technical-failure");

    @Override public void commandCompleted(String operation, String outcome) {
        if (!OPERATIONS.contains(operation) || !OUTCOMES.contains(outcome)) return;
        String previousEvent = MDC.get("event_name");
        String previousDiagnostic = MDC.get("diagnostic_id");
        try {
            MDC.put("event_name", "order_command_completed");
            MDC.put("diagnostic_id", operation + ":" + outcome);
            LOG.info("order telemetry");
        } finally {
            restore("event_name", previousEvent); restore("diagnostic_id", previousDiagnostic);
        }
    }
    private static void restore(String key, String value) { if (value == null) MDC.remove(key); else MDC.put(key, value); }
}
