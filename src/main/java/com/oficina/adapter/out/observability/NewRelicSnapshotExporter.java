package com.oficina.adapter.out.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.application.observability.SnapshotExporter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded New Relic Event API adapter. The ingest key only becomes an HTTP header at this boundary. */
public final class NewRelicSnapshotExporter implements SnapshotExporter {
    public static final int MAX_EVENTS_PER_BATCH = 32;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private final URI endpoint;
    private final String accountId;
    private final String ingestKey;
    private final HttpTransport transport;
    private final ObjectMapper mapper;
    private final AtomicLong droppedBatches = new AtomicLong();

    public NewRelicSnapshotExporter(URI endpoint, String accountId, String ingestKey,
                                    HttpTransport transport, ObjectMapper mapper) {
        this.endpoint = requireHttps(endpoint);
        this.accountId = required(accountId, "accountId");
        this.ingestKey = required(ingestKey, "ingestKey");
        this.transport = Objects.requireNonNull(transport);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override public void exportar(List<Map<String, Object>> eventos) {
        if (eventos == null || eventos.isEmpty()) return;
        if (eventos.size() > MAX_EVENTS_PER_BATCH) throw new IllegalArgumentException("Snapshot batch exceeds bound");
        eventos.forEach(NewRelicSnapshotExporter::validate);
        try {
            transport.post(endpoint, accountId, ingestKey, mapper.writeValueAsBytes(eventos), HTTP_TIMEOUT);
        } catch (JsonProcessingException error) {
            droppedBatches.incrementAndGet();
            throw new IllegalArgumentException("Snapshot event cannot be encoded", error);
        } catch (RuntimeException error) {
            droppedBatches.incrementAndGet();
            throw error;
        }
    }

    public long droppedBatches() { return droppedBatches.get(); }

    private static void validate(Map<String, Object> event) {
        if (event == null || !(event.get("eventType") instanceof String type) || type.isBlank()
                || !(event.get("environment") instanceof String environment)
                || !List.of("staging", "production").contains(environment)
                || !(event.get("timestamp") instanceof Number)) {
            throw new IllegalArgumentException("Every snapshot requires eventType, environment and capture timestamp");
        }
    }

    private static URI requireHttps(URI value) {
        if (value == null || !"https".equals(value.getScheme()) || value.getHost() == null) {
            throw new IllegalArgumentException("New Relic endpoint must be a concrete HTTPS URI");
        }
        return value;
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    @FunctionalInterface public interface HttpTransport {
        void post(URI endpoint, String accountId, String ingestKey, byte[] body, Duration timeout);
    }

    /** JDK-only transport with no retry policy. */
    public static final class JdkHttpTransport implements HttpTransport {
        private final HttpClient client;
        public JdkHttpTransport(HttpClient client) { this.client = Objects.requireNonNull(client); }
        @Override public void post(URI endpoint, String accountId, String ingestKey, byte[] body, Duration timeout) {
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                        .header("Api-Key", ingestKey).header("Content-Type", "application/json")
                        .header("X-Account-Id", accountId)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status < 200 || status >= 300) throw new IllegalStateException("Snapshot endpoint rejected batch");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Snapshot transport interrupted", error);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Snapshot transport unavailable", error);
            }
        }
    }
}
