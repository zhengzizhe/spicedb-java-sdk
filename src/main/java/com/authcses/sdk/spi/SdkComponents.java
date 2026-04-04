package com.authcses.sdk.spi;

import com.authcses.sdk.cache.Cache;
import com.authcses.sdk.model.CheckKey;
import com.authcses.sdk.model.CheckResult;

/**
 * Registry for pluggable SDK components.
 *
 * <pre>
 * .components(SdkComponents.builder()
 *     .l2Cache(myRedisCache)              // L2 distributed cache
 *     .telemetrySink(myKafkaSink)         // custom telemetry export
 *     .clock(new SdkClock.Fixed(0))       // test clock
 *     .build())
 * </pre>
 */
public record SdkComponents(
        Cache<CheckKey, CheckResult> l2Cache,
        TelemetrySink telemetrySink,
        SdkClock clock,
        DistributedTokenStore tokenStore
) {
    public static SdkComponents defaults() {
        return new SdkComponents(null, TelemetrySink.NOOP, SdkClock.SYSTEM, null);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Cache<CheckKey, CheckResult> l2Cache;
        private TelemetrySink telemetrySink = TelemetrySink.NOOP;
        private SdkClock clock = SdkClock.SYSTEM;
        private DistributedTokenStore tokenStore;

        /** L2 distributed cache (Redis, Hazelcast). null = no L2, L1 only. */
        public Builder l2Cache(Cache<CheckKey, CheckResult> l2Cache) { this.l2Cache = l2Cache; return this; }

        /** Telemetry export sink (Kafka, OTLP, file). Default: noop. */
        public Builder telemetrySink(TelemetrySink sink) { this.telemetrySink = sink; return this; }

        /** Custom clock (for testing). Default: system clock. */
        public Builder clock(SdkClock clock) { this.clock = clock; return this; }

        /** Distributed token store (Redis) for cross-instance SESSION consistency. null = local only. */
        public Builder tokenStore(DistributedTokenStore store) { this.tokenStore = store; return this; }

        public SdkComponents build() { return new SdkComponents(l2Cache, telemetrySink, clock, tokenStore); }
    }
}
