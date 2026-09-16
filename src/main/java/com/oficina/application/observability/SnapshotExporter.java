package com.oficina.application.observability;

import java.util.List;
import java.util.Map;

/** Boundary for the vendor Event API.  Report calculation never depends on its availability. */
@FunctionalInterface
public interface SnapshotExporter {
    void exportar(List<Map<String, Object>> eventos);
}
