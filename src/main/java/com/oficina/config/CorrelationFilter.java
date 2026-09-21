package com.oficina.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

/** Sets only reviewed correlation metadata and always clears worker-thread MDC state. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationFilter extends OncePerRequestFilter {
    public static final String CORRELATION_ID = "correlation_id";
    private static final String TRACEPARENT = "traceparent";
    private static final String GATEWAY_ID = "api_gateway_request_id";
    // Outbox phase3-v1 stores correlationId as a UUID. Accepting any other wire form would make a
    // legitimate HTTP request fail only when it reaches a status mutation, so normalize at the boundary.
    private static final String CORRELATION_PATTERN = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
    // API Gateway's $context.requestId is base64-ish (observed: "EEFW1hBXoAMESEQ="), so the URL-safe
    // alphabet must allow up to two trailing '=' padding characters. Still a bounded whitelist: no
    // quotes, backslashes or control characters that could break the JSON log line or inject a field.
    private static final String GATEWAY_ID_PATTERN = "[A-Za-z0-9_-]{1,128}={0,2}";
    private static final String TRACE_PATTERN = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = validCorrelation(request.getHeader("X-Correlation-Id")) ? request.getHeader("X-Correlation-Id").toLowerCase() : UUID.randomUUID().toString();
        MDC.put(CORRELATION_ID, id);
        putIfValid(TRACEPARENT, request.getHeader("traceparent"), TRACE_PATTERN);
        // Header lookup is case-insensitive per the servlet API; spelled to match the API Gateway
        // integration's "overwrite:header.X-Gateway-Request-Id" request parameter for readability.
        putIfValid(GATEWAY_ID, request.getHeader("X-Gateway-Request-Id"), GATEWAY_ID_PATTERN);
        response.setHeader("X-Correlation-Id", id);
        try { chain.doFilter(request, response); }
        finally { MDC.remove(CORRELATION_ID); MDC.remove(TRACEPARENT); MDC.remove(GATEWAY_ID); }
    }
    public static String correlationId() {
        String id = MDC.get(CORRELATION_ID); return validCorrelation(id) ? id : UUID.randomUUID().toString();
    }
    public static String traceparent() {
        String trace = MDC.get(TRACEPARENT); return trace != null && trace.matches(TRACE_PATTERN) ? trace : null;
    }
    private static boolean validCorrelation(String value) { return value != null && value.matches(CORRELATION_PATTERN); }
    private static void putIfValid(String key, String value, String pattern) { if (value != null && value.matches(pattern)) MDC.put(key, value); }
}
