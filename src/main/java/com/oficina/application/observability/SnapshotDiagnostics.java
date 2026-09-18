package com.oficina.application.observability;

/** Safe aggregate diagnostics. No event payload, recipient, token or exception text crosses this boundary. */
public record SnapshotDiagnostics(long outboxPending, long outboxBlocked, long outboxOldestSeconds, long droppedExports) {
    public SnapshotDiagnostics {
        if (outboxPending < 0 || outboxBlocked < 0 || outboxOldestSeconds < 0 || droppedExports < 0) {
            throw new IllegalArgumentException("Snapshot diagnostics must be non-negative");
        }
    }

    public static SnapshotDiagnostics unavailable(long droppedExports) {
        return new SnapshotDiagnostics(0, 0, 0, droppedExports);
    }
}
