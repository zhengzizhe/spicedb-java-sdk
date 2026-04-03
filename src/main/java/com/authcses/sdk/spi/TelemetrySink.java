package com.authcses.sdk.spi;

import java.util.List;
import java.util.Map;

/**
 * SPI for telemetry export. Business code implements this to send SDK events
 * to their observability stack (Kafka, OTLP, file, etc.).
 *
 * Default: NoopTelemetrySink (no export).
 */
public interface TelemetrySink {

    void send(List<Map<String, Object>> events);

    TelemetrySink NOOP = events -> {};
}
