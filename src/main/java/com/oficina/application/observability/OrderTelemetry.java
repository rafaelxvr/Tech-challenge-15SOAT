package com.oficina.application.observability;

/** Application port: business code expresses bounded outcomes without importing a telemetry SDK. */
public interface OrderTelemetry {
    void commandCompleted(String operation, String outcome);
}
