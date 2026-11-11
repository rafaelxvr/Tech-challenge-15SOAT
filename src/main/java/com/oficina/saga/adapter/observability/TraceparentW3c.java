package com.oficina.saga.adapter.observability;

import com.newrelic.api.agent.TraceMetadata;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds a W3C {@code traceparent} from the ids of the transaction that is active right now. Nothing is invented: a
 * missing, malformed or all-zero id yields an empty carrier instead of a made-up one.
 */
final class TraceparentW3c {
    private static final Pattern TRACE_ID = Pattern.compile("(?!0{32})[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("(?!0{16})[0-9a-f]{16}");

    private TraceparentW3c() {}

    static Map<String, String> de(TraceMetadata metadata) {
        if (metadata == null) return Map.of();
        String traceId = metadata.getTraceId();
        String spanId = metadata.getSpanId();
        if (traceId == null || spanId == null) return Map.of();
        if (!TRACE_ID.matcher(traceId).matches() || !SPAN_ID.matcher(spanId).matches()) return Map.of();
        return Map.of("traceparent", "00-" + traceId + "-" + spanId + (metadata.isSampled() ? "-01" : "-00"));
    }
}
