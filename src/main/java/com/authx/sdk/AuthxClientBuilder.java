package com.authx.sdk;

import com.authx.sdk.builtin.ValidationInterceptor;
import com.authx.sdk.cache.Cache;
import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.internal.SdkCaching;
import com.authx.sdk.internal.SdkConfig;
import com.authx.sdk.internal.SdkInfrastructure;
import com.authx.sdk.internal.SdkObservability;
import com.authx.sdk.lifecycle.LifecycleManager;
import com.authx.sdk.lifecycle.SdkPhase;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.health.ChannelStateHealthProbe;
import com.authx.sdk.health.SchemaReadHealthProbe;
import com.authx.sdk.spi.DuplicateDetector;
import com.authx.sdk.spi.HealthProbe;
import com.authx.sdk.spi.SdkComponents;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.telemetry.TelemetryReporter;
import com.authx.sdk.transport.CachedTransport;
import com.authx.sdk.transport.CoalescingTransport;
import com.authx.sdk.transport.GrpcTransport;
import com.authx.sdk.transport.InstrumentedTransport;
import com.authx.sdk.transport.InterceptorTransport;
import com.authx.sdk.transport.PolicyAwareConsistencyTransport;
import com.authx.sdk.transport.ResilientTransport;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SchemaLoader;
import com.authx.sdk.transport.StaticNameResolver;
import com.authx.sdk.transport.TokenTracker;
import com.authx.sdk.transport.WatchCacheInvalidator;
import com.authx.sdk.watch.WatchDispatcher;
import com.authx.sdk.watch.WatchStrategy;
import com.github.benmanes.caffeine.cache.Expiry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Builder for {@link AuthxClient}.
 *
 * <pre>
 * AuthxClient.builder()
 *     .connection(c -> c
 *         .target("dns:///spicedb.prod:50051")
 *         .presharedKey("my-key")
 *         .tls(true))
 *     .cache(c -> c
 *         .enabled(true)
 *         .maxSize(100_000)
 *         .watchInvalidation(true))
 *     .build();
 * </pre>
 */
public class AuthxClientBuilder {
    // Connection
    private String target;
    private List<String> targets;
    private String presharedKey;
    private boolean useTls = false;
    private String loadBalancing = "round_robin";
    private Duration keepAliveTime = Duration.ofSeconds(30);
    private Duration requestTimeout = Duration.ofSeconds(5);

    // Cache
    private boolean cacheEnabled = false;
    private long cacheMaxSize = 100_000;
    private boolean watchInvalidation = false;
    private com.authx.sdk.spi.QueueFullPolicy listenerQueueOnFull =
            com.authx.sdk.spi.QueueFullPolicy.DROP;

    // Features
    private boolean coalescingEnabled = true;
    private boolean useVirtualThreads = false;
    private boolean registerShutdownHook = false;
    private boolean telemetryEnabled = false;
    private String defaultSubjectType = "user";

    // Extensibility
    private PolicyRegistry policyRegistry;
    private TypedEventBus eventBus;
    private SdkComponents components;
    private final List<SdkInterceptor> interceptors = new ArrayList<>();
    private final List<WatchStrategy> watchStrategies = new ArrayList<>();
    private final List<com.authx.sdk.spi.PolicyCustomizer> policyCustomizers = new ArrayList<>();
    private final List<com.authx.sdk.spi.AuthxClientCustomizer> clientCustomizers = new ArrayList<>();

    // ============================================================
    //  Grouped configuration (lambda style)
    // ============================================================

    /** Connection settings. */
    public AuthxClientBuilder connection(Consumer<ConnectionConfig> config) {
        config.accept(new ConnectionConfig());
        return this;
    }

    /** Cache settings. */
    public AuthxClientBuilder cache(Consumer<CacheConfig> config) {
        config.accept(new CacheConfig());
        return this;
    }

    /** Feature toggles. */
    public AuthxClientBuilder features(Consumer<FeatureConfig> config) {
        config.accept(new FeatureConfig());
        return this;
    }

    /** Extensibility (policies, SPI, interceptors). */
    public AuthxClientBuilder extend(Consumer<ExtendConfig> config) {
        config.accept(new ExtendConfig());
        return this;
    }

    /**
     * Apply a business-level {@link com.authx.sdk.spi.PolicyCustomizer}.
     *
     * <p>Customizers run against a fresh {@link PolicyRegistry.Builder} at
     * {@link #build()} time, in the order they were registered. Use this
     * to separate business policy decisions (cache TTL, consistency, retry
     * budgets) from infrastructure config — see the {@code PolicyCustomizer}
     * javadoc for the full rationale.
     *
     * <p>Multiple customizers compose: register a "sane defaults" customizer
     * first, then an override customizer on top. The last caller wins on
     * any given field because of how the builder assignment semantics work.
     *
     * <p>Calling both {@code .extend(e -> e.policies(...))} (setting a
     * concrete registry directly) AND {@code .customize(policyCustomizer)}
     * is not recommended: the direct registry wins and the customizers are
     * silently ignored.
     */
    public AuthxClientBuilder customize(com.authx.sdk.spi.PolicyCustomizer customizer) {
        policyCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /**
     * Apply an {@link com.authx.sdk.spi.AuthxClientCustomizer} — the
     * general-purpose escape hatch that sees the whole builder. Runs at
     * the start of {@link #build()}, before any config is read. Use this
     * when {@link #customize(com.authx.sdk.spi.PolicyCustomizer)} is too
     * narrow — e.g. to inject an interceptor, a {@code tokenStore}, or a
     * custom {@code HealthProbe}.
     */
    public AuthxClientBuilder customize(com.authx.sdk.spi.AuthxClientCustomizer customizer) {
        clientCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    public class ConnectionConfig {
        public ConnectionConfig target(String t) { AuthxClientBuilder.this.target = t; return this; }
        public ConnectionConfig targets(String... t) { AuthxClientBuilder.this.targets = List.of(t); return this; }
        public ConnectionConfig presharedKey(String k) { AuthxClientBuilder.this.presharedKey = k; return this; }
        public ConnectionConfig tls(boolean t) { AuthxClientBuilder.this.useTls = t; return this; }
        public ConnectionConfig loadBalancing(String p) { AuthxClientBuilder.this.loadBalancing = p; return this; }
        public ConnectionConfig keepAliveTime(Duration d) { AuthxClientBuilder.this.keepAliveTime = d; return this; }
        public ConnectionConfig requestTimeout(Duration d) { AuthxClientBuilder.this.requestTimeout = d; return this; }
    }

    public class CacheConfig {
        public CacheConfig enabled(boolean e) { AuthxClientBuilder.this.cacheEnabled = e; return this; }
        public CacheConfig maxSize(long s) { AuthxClientBuilder.this.cacheMaxSize = s; return this; }
        public CacheConfig watchInvalidation(boolean e) { AuthxClientBuilder.this.watchInvalidation = e; return this; }
        /**
         * Policy for the SDK-owned Watch listener executor when its dispatch
         * queue fills up (SR:C5). Default:
         * {@link com.authx.sdk.spi.QueueFullPolicy#DROP} — matches pre-SR:C5
         * behavior.
         *
         * <p>Switch to {@link com.authx.sdk.spi.QueueFullPolicy#BLOCK_WITH_BACKPRESSURE}
         * when listener event loss is unacceptable and you want the Watch
         * stream (and upstream SpiceDB, via HTTP/2 flow control) to slow
         * down to match listener throughput.
         *
         * <p>Has no effect when a user-supplied {@code watchListenerExecutor}
         * is provided via {@code extend.components(...)}.
         */
        public CacheConfig listenerQueueOnFull(com.authx.sdk.spi.QueueFullPolicy policy) {
            AuthxClientBuilder.this.listenerQueueOnFull = policy != null
                    ? policy : com.authx.sdk.spi.QueueFullPolicy.DROP;
            return this;
        }
    }

    public class FeatureConfig {
        public FeatureConfig coalescing(boolean e) { AuthxClientBuilder.this.coalescingEnabled = e; return this; }
        public FeatureConfig virtualThreads(boolean e) { AuthxClientBuilder.this.useVirtualThreads = e; return this; }
        public FeatureConfig shutdownHook(boolean e) { AuthxClientBuilder.this.registerShutdownHook = e; return this; }
        public FeatureConfig telemetry(boolean e) { AuthxClientBuilder.this.telemetryEnabled = e; return this; }
        public FeatureConfig defaultSubjectType(String t) { AuthxClientBuilder.this.defaultSubjectType = t; return this; }
    }

    public class ExtendConfig {
        public ExtendConfig policies(PolicyRegistry p) { AuthxClientBuilder.this.policyRegistry = p; return this; }
        public ExtendConfig eventBus(TypedEventBus b) { AuthxClientBuilder.this.eventBus = b; return this; }
        public ExtendConfig components(SdkComponents c) { AuthxClientBuilder.this.components = c; return this; }
        public ExtendConfig addInterceptor(SdkInterceptor i) { AuthxClientBuilder.this.interceptors.add(i); return this; }
        /** Register a Watch strategy for a resource type. Requires watchInvalidation(true). */
        public ExtendConfig addWatchStrategy(WatchStrategy s) { AuthxClientBuilder.this.watchStrategies.add(s); return this; }
    }

    /** Mutable holder for intermediate build artifacts. Replaces single-element array hacks. */
    private static class BuildContext {
        Cache<CheckKey, CheckResult> checkCache;
        TelemetryReporter telemetryReporter;
        ResilientTransport resilientTransport;
        ScheduledExecutorService scheduler;
        WatchCacheInvalidator watchInvalidator;
    }

    /** Build and return a fully initialized {@link AuthxClient} connected to SpiceDB. */
    public AuthxClient build() {
        // Apply general client customizers FIRST, so they see all the
        // infrastructure config the caller already set and can layer on
        // top (add interceptors, inject components, etc.). Running them
        // before the validation step below lets a customizer even fix
        // missing config — e.g. a test harness injecting a stub target.
        for (var c : clientCustomizers) {
            c.customize(this);
        }

        Objects.requireNonNull(presharedKey, "presharedKey is required");
        if (target == null && targets == null) throw new IllegalArgumentException("target or targets is required");
        // SR:C6 — target and targets are mutually exclusive. Prior to this check the
        // builder silently preferred `target` when both were set (see buildChannel below),
        // which masked user misconfiguration (e.g. copy-paste of both forms during
        // staging → prod migration).
        if (target != null && targets != null) {
            throw new IllegalArgumentException(
                    "target and targets are mutually exclusive — pick one");
        }
        // SR:C7 — watchInvalidation only takes effect when the check cache is enabled.
        // Historically a misconfigured builder would silently no-op Watch; fail fast so
        // the operator learns at startup, not when debugging stale authz decisions.
        if (watchInvalidation && !cacheEnabled) {
            throw new IllegalArgumentException(
                    "cache.watchInvalidation(true) requires cache.enabled(true)");
        }
        if (!watchStrategies.isEmpty() && (!cacheEnabled || !watchInvalidation)) {
            throw new IllegalArgumentException(
                    "extend.watchStrategy(...) requires cache.enabled(true) and cache.watchInvalidation(true)");
        }

        // Resolve the effective PolicyRegistry. Precedence:
        //   1. An explicit registry passed via .extend(e -> e.policies(...))
        //      wins outright — explicit > implicit.
        //   2. Otherwise, build a fresh Builder seeded with SDK defaults and
        //      run every registered PolicyCustomizer against it. This is the
        //      preferred path: infrastructure config supplies the builder,
        //      business teams supply the customizers, neither needs to know
        //      about the other.
        //   3. If neither is configured, fall back to PolicyRegistry.withDefaults().
        final PolicyRegistry policies;
        if (policyRegistry != null) {
            policies = policyRegistry;
        } else if (!policyCustomizers.isEmpty()) {
            var pBuilder = PolicyRegistry.builder();
            for (var c : policyCustomizers) {
                c.customize(pBuilder);
            }
            policies = pBuilder.build();
        } else {
            policies = PolicyRegistry.withDefaults();
        }
        var spi = components != null ? components : SdkComponents.defaults();
        var bus = eventBus != null ? eventBus : new DefaultTypedEventBus();
        var lm = new LifecycleManager(bus);
        var sdkMetrics = new SdkMetrics();
        var schemaCache = new SchemaCache();
        var schemaLoader = new SchemaLoader();
        var tokenTracker = new TokenTracker(spi.tokenStore());
        // Wire the event bus so cross-instance SESSION degradation/recovery
        // become observable. Must use the resolved `bus` (not the nullable
        // `eventBus` field) so subscribers on AuthxClient.eventBus() receive
        // events even when the user didn't explicitly configure a bus.
        tokenTracker.setEventBus(bus);

        // Build the effective interceptor list locally — MUST NOT mutate the
        // builder's own field, otherwise calling build() twice on the same
        // builder would stack duplicate ValidationInterceptors.
        final List<SdkInterceptor> effectiveInterceptors = new ArrayList<>(interceptors.size() + 1);
        effectiveInterceptors.add(new ValidationInterceptor());
        effectiveInterceptors.addAll(interceptors);

        // Hoisted outside the try block so the catch can unwind partially-
        // constructed resources (watchInvalidator, scheduler, telemetry
        // reporter) regardless of which phase threw.
        final BuildContext ctx = new BuildContext();
        ManagedChannel channel = null;

        try {
            lm.begin();

            // Phase: CHANNEL — single HTTP/2 connection with multiplexing
            // gRPC + HTTP/2 supports thousands of concurrent streams per connection.
            // With DNS + round_robin, gRPC auto-creates subchannels to each backend.
            final ManagedChannel grpcChannel = lm.phase(
                    SdkPhase.CHANNEL, () -> buildChannel());
            channel = grpcChannel;

            // Phase: SCHEMA. authMeta is not reassigned so it is effectively
            // final for the lambdas below — no alias needed.
            final Metadata authMeta = new Metadata();
            authMeta.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + presharedKey);
            lm.phase(SdkPhase.SCHEMA, () ->
                    schemaLoader.load(grpcChannel, authMeta, schemaCache));
            // Enable on-miss refresh for schema validation
            schemaCache.setRefreshCallback(() ->
                    schemaLoader.load(grpcChannel, authMeta, schemaCache));

            // Phase: TRANSPORT
            SdkTransport transport = lm.phase(SdkPhase.TRANSPORT, () ->
                    buildTransportStack(grpcChannel, policies, spi, bus, sdkMetrics,
                            tokenTracker, effectiveInterceptors, ctx));
            if (ctx.resilientTransport != null) {
                sdkMetrics.setCircuitBreakerStateSupplier(
                        () -> ctx.resilientTransport.getCircuitBreakerState("_default").name());
            }

            // Phase: WATCH
            lm.phase(SdkPhase.WATCH, () ->
                    buildWatch(grpcChannel, sdkMetrics, spi, ctx, bus));

            // Phase: SCHEDULER
            lm.phase(SdkPhase.SCHEDULER, () ->
                    buildScheduler(grpcChannel, authMeta, schemaLoader, schemaCache, sdkMetrics, ctx));

            lm.complete();

            // Warn: SESSION consistency without distributed token store
            if (spi.tokenStore() == null) {
                System.getLogger(AuthxClient.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "No DistributedTokenStore configured — SESSION consistency only works " +
                        "within a single JVM. For multi-instance deployments, provide a Redis-backed " +
                        "tokenStore via .extend(e -> e.components(SdkComponents.builder()" +
                        ".tokenStore(redisStore).build()))");
            }

            // Build Watch dispatcher (strategies → dispatcher → watchInvalidator)
            var dispatcher = !watchStrategies.isEmpty()
                    ? new WatchDispatcher(watchStrategies)
                    : ctx.watchInvalidator != null ? new WatchDispatcher(List.of()) : null;

            // Async executor: virtual threads if enabled, otherwise direct (caller thread)
            Executor asyncExec = useVirtualThreads
                    ? Executors.newVirtualThreadPerTaskExecutor()
                    : Runnable::run;

            // Build aggregation objects
            var infraObj = new SdkInfrastructure(grpcChannel, ctx.scheduler, asyncExec, lm);
            var observabilityObj = new SdkObservability(sdkMetrics, bus, ctx.telemetryReporter);
            var cachingObj = new SdkCaching(ctx.checkCache, schemaCache, ctx.watchInvalidator, dispatcher);
            var configObj = new SdkConfig(defaultSubjectType, policies, coalescingEnabled, useVirtualThreads);

            // Resolve health probe: user-provided takes precedence, otherwise
            // default to a composite of channel-state + schema-read so diagnostics
            // show both the local channel health and end-to-end SpiceDB reachability.
            HealthProbe probe = spi.healthProbe();
            if (probe == null) {
                probe = HealthProbe.all(
                        new ChannelStateHealthProbe(grpcChannel),
                        new SchemaReadHealthProbe(grpcChannel, presharedKey));
            }

            var client = new AuthxClient(transport, infraObj, observabilityObj, cachingObj, configObj, probe);

            if (registerShutdownHook) {
                var hook = new Thread(client::close, "authx-sdk-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                infraObj.setShutdownHook(hook);
            }

            return client;
        } catch (Exception e) {
            // Unwind partially-constructed resources. ctx is visible here
            // because it was hoisted above the try block — this is intentional:
            // buildWatch() may assign ctx.watchInvalidator and then have
            // start() throw, in which case the local variable (if we'd used
            // one) would still be null but ctx.watchInvalidator would be the
            // leaked reference. Always close via ctx.
            if (ctx.scheduler != null) {
                ctx.scheduler.shutdown();
                try { ctx.scheduler.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            if (ctx.watchInvalidator != null) {
                try { ctx.watchInvalidator.close(); } catch (Exception ignored) {}
            }
            if (ctx.telemetryReporter != null) {
                try { ctx.telemetryReporter.close(); } catch (Exception ignored) {}
            }
            if (channel != null) {
                channel.shutdown();
                try { channel.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            throw e;
        }
    }

    /**
     * Build the transport decoration stack (innermost → outermost):
     *   GrpcTransport → ResilientTransport → InstrumentedTransport → CachedTransport
     *   → PolicyAwareConsistencyTransport → CoalescingTransport → InterceptorTransport
     *
     * Order matters: CachedTransport must sit BELOW PolicyAwareConsistencyTransport
     * so that write-after-read consistency tokens bypass the cache correctly.
     */
    private SdkTransport buildTransportStack(ManagedChannel grpcChannel, PolicyRegistry policies,
            SdkComponents spi, TypedEventBus bus, SdkMetrics sdkMetrics,
            TokenTracker tokenTracker, List<SdkInterceptor> effectiveInterceptors, BuildContext ctx) {
        SdkTransport t = new GrpcTransport(grpcChannel, presharedKey, requestTimeout.toMillis());

        // Resilience (circuit breaker + retry via Resilience4j).
        //
        // Metric-recording ownership (F11-1): exactly ONE layer in the stack
        // should call sdkMetrics.recordRequest(), otherwise every miss is
        // counted twice and the histogram contains two points per request.
        // When telemetry is enabled, InstrumentedTransport wraps this layer
        // and is the outermost recorder (it sees retry-inclusive latency, which
        // is what operators actually want). In that case we pass null sdkMetrics
        // to ResilientTransport so it skips recording. When telemetry is
        // disabled, ResilientTransport is the outermost layer in the stack and
        // owns the recording itself.
        var resilientMetrics = telemetryEnabled ? null : sdkMetrics;
        var resilientTransport = new ResilientTransport(t, policies, bus, resilientMetrics);
        ctx.resilientTransport = resilientTransport;
        t = resilientTransport;

        if (telemetryEnabled) {
            ctx.telemetryReporter = new TelemetryReporter(spi.telemetrySink(), useVirtualThreads);
            t = new InstrumentedTransport(t, ctx.telemetryReporter, sdkMetrics);
        }

        if (cacheEnabled) {
            Cache<CheckKey, CheckResult> effectiveCache;
            try {
                // Policy-aware variable expiry: per-(resourceType, permission) TTL
                var expiry = new Expiry<CheckKey, CheckResult>() {
                    @Override
                    public long expireAfterCreate(CheckKey key, CheckResult value, long currentTime) {
                        long baseNanos = policies.resolveCacheTtl(
                                key.resource().type(), key.permission().name()).toNanos();
                        // ±10% jitter to prevent cache stampede
                        long jitter = java.util.concurrent.ThreadLocalRandom.current()
                                .nextLong(-baseNanos / 10, baseNanos / 10 + 1);
                        return baseNanos + jitter;
                    }
                    @Override
                    public long expireAfterUpdate(CheckKey key, CheckResult value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                    @Override
                    public long expireAfterRead(CheckKey key, CheckResult value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                };
                var l1 = new CaffeineCache<>(cacheMaxSize, expiry, CheckKey::resourceIndex);
                effectiveCache = l1;
            } catch (NoClassDefFoundError e) {
                System.getLogger(AuthxClient.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Cache enabled but Caffeine not on classpath. Add dependency: " +
                        "com.github.ben-manes.caffeine:caffeine:3.1.8. Falling back to no-op cache.");
                effectiveCache = Cache.noop();
            }

            ctx.checkCache = effectiveCache;
            t = new CachedTransport(t, effectiveCache, sdkMetrics);
        }

        if (ctx.checkCache != null) {
            var cacheRef = ctx.checkCache;
            sdkMetrics.setCacheStatsSource(cacheRef::stats);
        }

        t = new PolicyAwareConsistencyTransport(t, policies, tokenTracker);
        if (coalescingEnabled) t = new CoalescingTransport(t, sdkMetrics);
        if (!effectiveInterceptors.isEmpty()) t = new InterceptorTransport(t, effectiveInterceptors);
        return t;
    }

    private void buildWatch(ManagedChannel grpcChannel, SdkMetrics sdkMetrics,
                             SdkComponents spi, BuildContext ctx, TypedEventBus bus) {
        if (cacheEnabled && watchInvalidation && ctx.checkCache != null) {
            // Resolve the duplicate detector: user-provided via SdkComponents wins,
            // otherwise fall back to noop (backwards-compatible behavior — no dedup).
            // Consumers who want cursor-replay dedup should configure:
            //   .extend(e -> e.components(SdkComponents.builder()
            //       .watchDuplicateDetector(DuplicateDetector.lru(10_000, Duration.ofMinutes(5)))
            //       .build()))
            DuplicateDetector<String> dedup = spi.watchDuplicateDetector();
            if (dedup == null) {
                dedup = DuplicateDetector.noop();
            }
            // listenerExecutor: null means "use SDK default (owned)", non-null means
            // "user manages lifecycle (NOT owned)". See WatchCacheInvalidator constructor.
            // dropHandler + queueFullPolicy (SR:C5) only affect the SDK-owned default
            // executor; when the user supplies their own executor, their rejection
            // handler is in charge.
            ctx.watchInvalidator = new WatchCacheInvalidator(
                    grpcChannel, presharedKey, ctx.checkCache, sdkMetrics,
                    dedup, spi.watchListenerExecutor(),
                    spi.watchListenerDropHandler(), listenerQueueOnFull);
            // Wire the typed event bus so cursor-expiry data-loss windows
            // become observable. Use the resolved `bus` (not the field) so
            // events flow even when the user didn't set an explicit bus.
            ctx.watchInvalidator.setEventBus(bus);
            ctx.watchInvalidator.start();
        }
    }

    private void buildScheduler(ManagedChannel grpcChannel, Metadata authMeta,
            SchemaLoader schemaLoader, SchemaCache schemaCache, SdkMetrics sdkMetrics,
            BuildContext ctx) {
        ThreadFactory tf = useVirtualThreads
                ? Thread.ofVirtual().name("authx-sdk-", 0).factory()
                : r -> { Thread th = new Thread(r, "authx-sdk-refresh"); th.setDaemon(true); return th; };
        ctx.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        // Refresh schema every 5 minutes
        ctx.scheduler.scheduleAtFixedRate(
                () -> schemaLoader.load(grpcChannel, authMeta, schemaCache),
                300, 300, TimeUnit.SECONDS);
        // Rotate metrics histogram every 5 seconds (single consumer, no contention)
        ctx.scheduler.scheduleAtFixedRate(sdkMetrics::rotateHistogram, 5, 5, TimeUnit.SECONDS);
        // Sample cache size periodically (not on hot path)
        if (cacheEnabled && ctx.checkCache != null) {
            var cache = ctx.checkCache;
            ctx.scheduler.scheduleAtFixedRate(
                    () -> sdkMetrics.updateCacheSize(cache.size()), 5, 5, TimeUnit.SECONDS);
        }
    }

    private ManagedChannel buildChannel() {
        ManagedChannelBuilder<?> builder;
        if (targets != null && targets.size() > 1) {
            // Multi-address: custom static resolver
            builder = ManagedChannelBuilder.forTarget("static:///multi")
                    .nameResolverFactory(new StaticNameResolver.Provider(targets));
        } else {
            String t = target != null ? target : targets.getFirst();
            builder = ManagedChannelBuilder.forTarget(t);
        }
        builder.defaultLoadBalancingPolicy(loadBalancing);
        builder.keepAliveTime(keepAliveTime.toMillis(), TimeUnit.MILLISECONDS);
        builder.keepAliveTimeout(5, TimeUnit.SECONDS);
        builder.keepAliveWithoutCalls(true);
        if (useTls) {
            builder.useTransportSecurity();
        } else {
            builder.usePlaintext();
        }
        return builder.build();
    }
}
