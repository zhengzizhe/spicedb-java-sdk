package com.authx.sdk.spi;

import org.jspecify.annotations.Nullable;

/**
 * Registry for pluggable SDK components.
 *
 * <pre>
 * .components(SdkComponents.builder()
 *     .telemetrySink(myKafkaSink)                     // custom telemetry export
 *     .clock(new SdkClock.Fixed(0))                   // test clock
 *     .healthProbe(HealthProbe.all(                   // composite health check
 *         new ChannelStateHealthProbe(channel),
 *         new SchemaReadHealthProbe(channel, psk)))
 *     .tokenStore(sharedStore)                        // cross-JVM SESSION consistency
 *     .build())
 * </pre>
 *
 * <p>All fields are nullable — {@code null} means "use the SDK default" (see
 * {@link #defaults()}). The builder validates nothing; the SDK builder picks
 * defaults during {@code AuthxClient.build()}.
 *
 * <p>Note: Watch-specific SPI fields (watchDuplicateDetector,
 * watchListenerExecutor, watchListenerDropHandler) were removed with the
 * Watch subsystem in ADR 2026-04-18.
 */
public record SdkComponents(
        TelemetrySink telemetrySink,
        SdkClock clock,
        @Nullable DistributedTokenStore tokenStore,
        @Nullable HealthProbe healthProbe
) {
    public static SdkComponents defaults() {
        return new SdkComponents(TelemetrySink.NOOP, SdkClock.SYSTEM, null, null);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private TelemetrySink telemetrySink = TelemetrySink.NOOP;
        private SdkClock clock = SdkClock.SYSTEM;
        private DistributedTokenStore tokenStore;
        private HealthProbe healthProbe;

        /** Telemetry export sink (Kafka, OTLP, file). Default: noop. */
        public Builder telemetrySink(TelemetrySink sink) { this.telemetrySink = sink; return this; }

        /** Custom clock (for testing). Default: system clock. */
        public Builder clock(SdkClock clock) { this.clock = clock; return this; }

        /** User-provided distributed token store for cross-instance SESSION consistency. null = local only. */
        public Builder tokenStore(DistributedTokenStore store) { this.tokenStore = store; return this; }

        /**
         * Custom health probe used by {@link com.authx.sdk.AuthxClient#health()}.
         * When null, the SDK builder chooses a default probe appropriate for the client
         * (RPC-backed for real clients, always-up for in-memory clients).
         */
        public Builder healthProbe(HealthProbe probe) { this.healthProbe = probe; return this; }

        public SdkComponents build() {
            return new SdkComponents(telemetrySink, clock, tokenStore, healthProbe);
        }
    }
}
