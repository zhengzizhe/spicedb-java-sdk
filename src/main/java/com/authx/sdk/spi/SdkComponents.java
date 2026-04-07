package com.authx.sdk.spi;

import org.jspecify.annotations.Nullable;

/**
 * Registry for pluggable SDK components.
 *
 * <pre>
 * .components(SdkComponents.builder()
 *     .telemetrySink(myKafkaSink)         // custom telemetry export
 *     .clock(new SdkClock.Fixed(0))       // test clock
 *     .build())
 * </pre>
 */
public record SdkComponents(
        TelemetrySink telemetrySink,
        SdkClock clock,
        @Nullable DistributedTokenStore tokenStore
) {
    public static SdkComponents defaults() {
        return new SdkComponents(TelemetrySink.NOOP, SdkClock.SYSTEM, null);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private TelemetrySink telemetrySink = TelemetrySink.NOOP;
        private SdkClock clock = SdkClock.SYSTEM;
        private DistributedTokenStore tokenStore;

        /** Telemetry export sink (Kafka, OTLP, file). Default: noop. */
        public Builder telemetrySink(TelemetrySink sink) { this.telemetrySink = sink; return this; }

        /** Custom clock (for testing). Default: system clock. */
        public Builder clock(SdkClock clock) { this.clock = clock; return this; }

        /** Distributed token store (Redis) for cross-instance SESSION consistency. null = local only. */
        public Builder tokenStore(DistributedTokenStore store) { this.tokenStore = store; return this; }

        public SdkComponents build() { return new SdkComponents(telemetrySink, clock, tokenStore); }
    }
}
