package com.authcses.sdk.spi;

import com.authcses.sdk.cache.CheckCache;

/**
 * Registry for pluggable SDK components.
 *
 * <pre>
 * .components(SdkComponents.builder()
 *     .l2Cache(myRedisCheckCache)         // L2 distributed cache
 *     .telemetrySink(myKafkaSink)         // custom telemetry export
 *     .clock(new SdkClock.Fixed(0))       // test clock
 *     .build())
 * </pre>
 */
public record SdkComponents(
        CheckCache l2Cache,
        TelemetrySink telemetrySink,
        SdkClock clock
) {
    public static SdkComponents defaults() {
        return new SdkComponents(null, TelemetrySink.NOOP, SdkClock.SYSTEM);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private CheckCache l2Cache;
        private TelemetrySink telemetrySink = TelemetrySink.NOOP;
        private SdkClock clock = SdkClock.SYSTEM;

        /** L2 distributed cache (Redis, Hazelcast). null = no L2, L1 only. */
        public Builder l2Cache(CheckCache l2Cache) { this.l2Cache = l2Cache; return this; }

        /** Telemetry export sink (Kafka, OTLP, file). Default: noop. */
        public Builder telemetrySink(TelemetrySink sink) { this.telemetrySink = sink; return this; }

        /** Custom clock (for testing). Default: system clock. */
        public Builder clock(SdkClock clock) { this.clock = clock; return this; }

        public SdkComponents build() { return new SdkComponents(l2Cache, telemetrySink, clock); }
    }
}
