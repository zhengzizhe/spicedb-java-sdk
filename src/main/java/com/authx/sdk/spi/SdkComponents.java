package com.authx.sdk.spi;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.ExecutorService;

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
 *     .watchDuplicateDetector(                        // suppress Watch replay dupes
 *         DuplicateDetector.lru(10_000, Duration.ofMinutes(5)))
 *     .build())
 * </pre>
 *
 * <p>All fields are nullable — {@code null} means "use the SDK default" (see
 * {@link #defaults()}). The builder validates nothing; the SDK builder picks
 * defaults during {@code AuthxClient.build()}.
 */
public record SdkComponents(
        TelemetrySink telemetrySink,
        SdkClock clock,
        @Nullable DistributedTokenStore tokenStore,
        @Nullable HealthProbe healthProbe,
        @Nullable DuplicateDetector<String> watchDuplicateDetector,
        @Nullable ExecutorService watchListenerExecutor
) {
    public static SdkComponents defaults() {
        return new SdkComponents(TelemetrySink.NOOP, SdkClock.SYSTEM, null, null, null, null);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private TelemetrySink telemetrySink = TelemetrySink.NOOP;
        private SdkClock clock = SdkClock.SYSTEM;
        private DistributedTokenStore tokenStore;
        private HealthProbe healthProbe;
        private DuplicateDetector<String> watchDuplicateDetector;
        private ExecutorService watchListenerExecutor;

        /** Telemetry export sink (Kafka, OTLP, file). Default: noop. */
        public Builder telemetrySink(TelemetrySink sink) { this.telemetrySink = sink; return this; }

        /** Custom clock (for testing). Default: system clock. */
        public Builder clock(SdkClock clock) { this.clock = clock; return this; }

        /** Distributed token store (Redis) for cross-instance SESSION consistency. null = local only. */
        public Builder tokenStore(DistributedTokenStore store) { this.tokenStore = store; return this; }

        /**
         * Custom health probe used by {@link com.authx.sdk.AuthxClient#health()}.
         * When null, the SDK builder chooses a default probe appropriate for the client
         * (RPC-backed for real clients, always-up for in-memory clients).
         */
        public Builder healthProbe(HealthProbe probe) { this.healthProbe = probe; return this; }

        /**
         * Duplicate detector for Watch stream replays. When SpiceDB's Watch stream
         * reconnects via {@code optional_start_cursor}, it may replay events around
         * the cursor boundary; this detector suppresses redundant listener dispatch
         * while still allowing cache invalidation to run (which is idempotent).
         *
         * <p>Default ({@code null}): use {@link DuplicateDetector#noop()} — no
         * deduplication, matches pre-B3 behavior. Set to
         * {@link DuplicateDetector#lru(int, java.time.Duration)} for bounded-LRU
         * dedup (requires Caffeine on classpath).
         *
         * <p>Keyed on {@code zedToken}, which is SpiceDB's monotonic revision
         * marker and thus a natural unique identifier per Watch response.
         */
        public Builder watchDuplicateDetector(DuplicateDetector<String> detector) {
            this.watchDuplicateDetector = detector; return this;
        }

        /**
         * Custom {@link ExecutorService} for dispatching {@code RelationshipChange}
         * listener callbacks. When {@code null}, the SDK creates a single-threaded
         * bounded executor (1 thread, 10 000-element queue, drop-on-full policy)
         * suitable for in-process audit logging and metrics.
         *
         * <p>Provide your own executor if you need:
         * <ul>
         *   <li><b>Higher throughput / parallelism</b> — supply a {@code ForkJoinPool}
         *       or fixed thread pool with N threads. Listeners may be invoked
         *       concurrently for different events; ensure your listener is
         *       thread-safe.</li>
         *   <li><b>Different rejection policy</b> — supply an executor whose
         *       {@code RejectedExecutionHandler} blocks the caller (back-pressure
         *       all the way to SpiceDB) instead of dropping events.</li>
         *   <li><b>Larger / unbounded queue</b> — at your own risk; the SDK default
         *       is bounded to prevent OOM.</li>
         *   <li><b>Virtual threads</b> — pass {@code Executors.newVirtualThreadPerTaskExecutor()}
         *       on JDK 21+.</li>
         * </ul>
         *
         * <p><b>Lifecycle</b>: when you provide an executor, you also own its
         * shutdown — the SDK does NOT call {@code shutdown()} on it during
         * {@code AuthxClient.close()}. Conversely, the default executor is
         * managed by the SDK and shut down automatically.
         */
        public Builder watchListenerExecutor(ExecutorService executor) {
            this.watchListenerExecutor = executor; return this;
        }

        public SdkComponents build() {
            return new SdkComponents(telemetrySink, clock, tokenStore, healthProbe,
                    watchDuplicateDetector, watchListenerExecutor);
        }
    }
}
