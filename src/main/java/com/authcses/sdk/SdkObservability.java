package com.authcses.sdk;

import com.authcses.sdk.event.TypedEventBus;
import com.authcses.sdk.metrics.SdkMetrics;
import com.authcses.sdk.telemetry.TelemetryReporter;
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
