package com.authx.sdk;

import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.telemetry.TelemetryReporter;
import java.util.Objects;

/** Aggregates observability components: metrics, events, telemetry. */
public record SdkObservability(
    SdkMetrics metrics,
    TypedEventBus eventBus,
    TelemetryReporter telemetry
) {
    public SdkObservability {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(eventBus, "eventBus");
        // telemetry is nullable (disabled by default)
    }
}
