package com.authcses.sdk;

import com.authcses.sdk.cache.*;
import com.authcses.sdk.policy.PolicyRegistry;
import com.authcses.sdk.telemetry.TelemetryReporter;
import com.authcses.sdk.transport.*;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AuthCSES SDK — Java client for SpiceDB permission management.
 * Connects directly to SpiceDB via gRPC. No platform dependency.
 *
 * <pre>
 * var client = AuthCsesClient.builder()
 *     .target("dns:///spicedb.prod:50051")
 *     .presharedKey("my-key")
 *     .cacheEnabled(true)
 *     .build();
 * </pre>
 */
public class AuthCsesClient implements AutoCloseable {

    private final SdkTransport transport;
    private final io.grpc.ManagedChannel grpcChannel;
    private final com.authcses.sdk.cache.SchemaCache schemaCache;
    private final CheckCache checkCache;
    private final com.authcses.sdk.metrics.SdkMetrics sdkMetrics;
    private final com.authcses.sdk.event.SdkEventBus eventBus;
    private final com.authcses.sdk.lifecycle.LifecycleManager lifecycle;
    private final WatchCacheInvalidator watchInvalidator;
    private final TelemetryReporter telemetryReporter;
    private final ScheduledExecutorService scheduler;
    private volatile Thread shutdownHookRef;
    private final String defaultSubjectType;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final SchemaClient schemaClient;
    private final CacheHandle cacheHandle;

    AuthCsesClient(SdkTransport transport,
                   io.grpc.ManagedChannel grpcChannel,
                   SchemaCache schemaCache,
                   CheckCache checkCache,
                   com.authcses.sdk.metrics.SdkMetrics sdkMetrics,
                   com.authcses.sdk.event.SdkEventBus eventBus,
                   com.authcses.sdk.lifecycle.LifecycleManager lifecycle,
                   WatchCacheInvalidator watchInvalidator,
                   TelemetryReporter telemetryReporter,
                   ScheduledExecutorService scheduler,
                   String defaultSubjectType) {
        this.transport = Objects.requireNonNull(transport);
        this.grpcChannel = grpcChannel;
        this.schemaCache = schemaCache;
        this.checkCache = checkCache;
        this.sdkMetrics = sdkMetrics;
        this.eventBus = eventBus != null ? eventBus : new com.authcses.sdk.event.SdkEventBus();
        this.lifecycle = lifecycle != null ? lifecycle : new com.authcses.sdk.lifecycle.LifecycleManager(this.eventBus);
        this.watchInvalidator = watchInvalidator;
        this.telemetryReporter = telemetryReporter;
        this.scheduler = scheduler;
        this.defaultSubjectType = defaultSubjectType != null ? defaultSubjectType : "user";
        this.schemaClient = new SchemaClient(schemaCache);
        this.cacheHandle = new CacheHandle(checkCache);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AuthCsesClient inMemory() {
        var bus = new com.authcses.sdk.event.SdkEventBus();
        var lm = new com.authcses.sdk.lifecycle.LifecycleManager(bus);
        lm.begin(); lm.complete();
        return new AuthCsesClient(new InMemoryTransport(), null, null, null,
                new com.authcses.sdk.metrics.SdkMetrics(), bus, lm,
                null, null, null, "user");
    }

    // ---- Business API ----

    public ResourceHandle resource(String type, String id) {
        if (schemaCache != null) schemaCache.validateResourceType(type);
        return new ResourceHandle(type, id, transport, defaultSubjectType);
    }

    public LookupQuery lookup(String resourceType) {
        return new LookupQuery(resourceType, transport, defaultSubjectType);
    }

    public CrossResourceBatchBuilder batch() {
        return new CrossResourceBatchBuilder(transport, defaultSubjectType);
    }

    // ---- Observability ----

    public com.authcses.sdk.metrics.SdkMetrics metrics() { return sdkMetrics; }
    public com.authcses.sdk.event.SdkEventBus eventBus() { return eventBus; }
    public com.authcses.sdk.lifecycle.LifecycleManager lifecycle() { return lifecycle; }
    public SchemaClient schema() { return schemaClient; }
    public CacheHandle cache() { return cacheHandle; }

    public HealthResult health() {
        long start = System.nanoTime();
        try {
            transport.readRelationships("__health__", "__probe__", null,
                    com.authcses.sdk.model.Consistency.minimizeLatency());
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new HealthResult(true, ms, "SpiceDB reachable");
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new HealthResult(false, ms, "SpiceDB unreachable: " + e.getMessage());
        }
    }

    // ---- Lifecycle ----

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return; // prevent double-close
        eventBus.fire(com.authcses.sdk.event.SdkEvent.CLIENT_STOPPING, "SDK shutting down");
        lifecycle.stopping();

        if (scheduler != null) {
            scheduler.shutdown();
            try { if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) scheduler.shutdownNow(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); scheduler.shutdownNow(); }
        }
        if (watchInvalidator != null) watchInvalidator.close();
        if (telemetryReporter != null) telemetryReporter.close();
        transport.close();
        if (grpcChannel != null) {
            grpcChannel.shutdown();
            try { if (!grpcChannel.awaitTermination(3, TimeUnit.SECONDS)) grpcChannel.shutdownNow(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); grpcChannel.shutdownNow(); }
        }

        if (shutdownHookRef != null && Thread.currentThread() != shutdownHookRef) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHookRef); }
            catch (IllegalStateException ignored) {}
        }

        lifecycle.stopped();
        eventBus.fire(com.authcses.sdk.event.SdkEvent.CLIENT_STOPPED, "SDK stopped");
    }

    // ============================================================
    //  Builder
    // ============================================================

    public static class Builder {
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

        // Features
        private boolean coalescingEnabled = true;
        private boolean useVirtualThreads = false;
        private boolean registerShutdownHook = false;
        private boolean telemetryEnabled = false;
        private String defaultSubjectType = "user";

        // Extensibility
        private PolicyRegistry policyRegistry;
        private com.authcses.sdk.event.SdkEventBus eventBus;
        private com.authcses.sdk.spi.SdkComponents components;
        private final java.util.List<com.authcses.sdk.spi.SdkInterceptor> interceptors = new java.util.ArrayList<>();

        // ---- Connection ----
        public Builder target(String target) { this.target = target; return this; }
        public Builder targets(String... targets) { this.targets = List.of(targets); return this; }
        public Builder presharedKey(String key) { this.presharedKey = key; return this; }
        public Builder useTls(boolean tls) { this.useTls = tls; return this; }
        public Builder loadBalancing(String policy) { this.loadBalancing = policy; return this; }
        public Builder keepAliveTime(Duration d) { this.keepAliveTime = d; return this; }
        public Builder requestTimeout(Duration d) { this.requestTimeout = d; return this; }

        // ---- Cache ----
        public Builder cacheEnabled(boolean e) { this.cacheEnabled = e; return this; }
        public Builder cacheMaxSize(long s) { this.cacheMaxSize = s; return this; }
        public Builder watchInvalidation(boolean e) { this.watchInvalidation = e; return this; }

        // ---- Features ----
        public Builder coalescingEnabled(boolean e) { this.coalescingEnabled = e; return this; }
        public Builder useVirtualThreads(boolean e) { this.useVirtualThreads = e; return this; }
        public Builder registerShutdownHook(boolean e) { this.registerShutdownHook = e; return this; }
        public Builder telemetryEnabled(boolean e) { this.telemetryEnabled = e; return this; }
        public Builder defaultSubjectType(String t) { this.defaultSubjectType = t; return this; }

        // ---- Extensibility ----
        public Builder policies(PolicyRegistry p) { this.policyRegistry = p; return this; }
        public Builder eventBus(com.authcses.sdk.event.SdkEventBus b) { this.eventBus = b; return this; }
        public Builder components(com.authcses.sdk.spi.SdkComponents c) { this.components = c; return this; }
        public Builder addInterceptor(com.authcses.sdk.spi.SdkInterceptor i) { this.interceptors.add(i); return this; }

        public AuthCsesClient build() {
            Objects.requireNonNull(presharedKey, "presharedKey is required");
            if (target == null && targets == null) throw new IllegalArgumentException("target or targets is required");

            var policies = policyRegistry != null ? policyRegistry : PolicyRegistry.withDefaults();
            var spi = components != null ? components : com.authcses.sdk.spi.SdkComponents.defaults();
            var bus = eventBus != null ? eventBus : new com.authcses.sdk.event.SdkEventBus();
            var lm = new com.authcses.sdk.lifecycle.LifecycleManager(bus);
            var sdkMetrics = new com.authcses.sdk.metrics.SdkMetrics();
            var schemaCache = new SchemaCache();
            var tokenTracker = new TokenTracker();

            // Built-in interceptors
            interceptors.addFirst(new com.authcses.sdk.builtin.ValidationInterceptor());

            io.grpc.ManagedChannel channel = null;
            WatchCacheInvalidator watchInvalidator = null;
            TelemetryReporter telemetryReporter = null;
            ScheduledExecutorService scheduler = null;

            try {
                lm.begin();

                // Phase: CHANNEL
                final io.grpc.ManagedChannel grpcChannel = lm.phase(
                        com.authcses.sdk.lifecycle.SdkPhase.CHANNEL, () -> buildChannel());
                channel = grpcChannel;

                // Phase: SCHEMA
                io.grpc.Metadata authMeta = new io.grpc.Metadata();
                authMeta.put(io.grpc.Metadata.Key.of("authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + presharedKey);
                final io.grpc.Metadata authMetaFinal = authMeta;
                lm.phase(com.authcses.sdk.lifecycle.SdkPhase.SCHEMA, () ->
                        SchemaLoader.load(grpcChannel, authMetaFinal, schemaCache));

                // Phase: TRANSPORT
                final CheckCache[] cacheHolder = {null};
                final TelemetryReporter[] telemetryHolder = {null};
                SdkTransport transport = lm.phase(com.authcses.sdk.lifecycle.SdkPhase.TRANSPORT, () -> {
                    SdkTransport t = new GrpcTransport(grpcChannel, presharedKey, requestTimeout.toMillis());
                    t = new PolicyAwareRetryTransport(t, policies);

                    // Circuit breaker (between retry and telemetry)
                    var cbPolicy = policies.getDefaultPolicy().getCircuitBreaker();
                    if (cbPolicy != null && cbPolicy.isEnabled()) {
                        var breaker = new com.authcses.sdk.circuit.CircuitBreaker(
                                (int) cbPolicy.getFailureRateThreshold(),
                                cbPolicy.getWaitInOpenState(),
                                cbPolicy.getPermittedCallsInHalfOpen());
                        sdkMetrics.setCircuitBreaker(breaker);
                        t = new CircuitBreakerTransport(t, breaker, cbPolicy.getFailOpenPermissions());
                    }

                    if (telemetryEnabled) {
                        telemetryHolder[0] = new TelemetryReporter(spi.telemetrySink(), useVirtualThreads);
                        t = new InstrumentedTransport(t, telemetryHolder[0], sdkMetrics);
                    }

                    if (cacheEnabled) {
                        CheckCache l1;
                        try { l1 = new PolicyAwareCheckCache(policies, cacheMaxSize); }
                        catch (NoClassDefFoundError e) { l1 = CheckCache.noop(); }

                        cacheHolder[0] = spi.l2Cache() != null
                                ? new TwoLevelCache(l1, spi.l2Cache())
                                : l1;
                        t = new CachedTransport(t, cacheHolder[0], sdkMetrics);
                    }

                    t = new PolicyAwareConsistencyTransport(t, policies, tokenTracker);
                    if (coalescingEnabled) t = new CoalescingTransport(t, sdkMetrics);
                    if (!interceptors.isEmpty()) t = new InterceptorTransport(t, interceptors);
                    return t;
                });
                telemetryReporter = telemetryHolder[0];

                // Phase: WATCH
                final WatchCacheInvalidator[] watchHolder = {null};
                lm.phase(com.authcses.sdk.lifecycle.SdkPhase.WATCH, () -> {
                    if (cacheEnabled && watchInvalidation && cacheHolder[0] != null) {
                        watchHolder[0] = new WatchCacheInvalidator(
                                targets != null ? targets : List.of(target),
                                presharedKey, useTls, cacheHolder[0]);
                    }
                });
                watchInvalidator = watchHolder[0];

                // Phase: SCHEDULER (schema refresh)
                final ScheduledExecutorService[] schedHolder = {null};
                lm.phase(com.authcses.sdk.lifecycle.SdkPhase.SCHEDULER, () -> {
                    java.util.concurrent.ThreadFactory tf = useVirtualThreads
                            ? Thread.ofVirtual().name("authcses-sdk-", 0).factory()
                            : r -> { Thread th = new Thread(r, "authcses-sdk-refresh"); th.setDaemon(true); return th; };
                    schedHolder[0] = Executors.newSingleThreadScheduledExecutor(tf);
                    // Refresh schema every 5 minutes
                    schedHolder[0].scheduleAtFixedRate(
                            () -> SchemaLoader.load(grpcChannel, authMetaFinal, schemaCache),
                            300, 300, TimeUnit.SECONDS);
                });
                scheduler = schedHolder[0];

                lm.complete();

                var client = new AuthCsesClient(transport, grpcChannel, schemaCache, cacheHolder[0], sdkMetrics,
                        bus, lm, watchInvalidator, telemetryReporter, scheduler, defaultSubjectType);

                if (registerShutdownHook) {
                    var hook = new Thread(client::close, "authcses-sdk-shutdown");
                    Runtime.getRuntime().addShutdownHook(hook);
                    client.shutdownHookRef = hook;
                }

                return client;
            } catch (Exception e) {
                if (scheduler != null) scheduler.shutdownNow();
                if (watchInvalidator != null) watchInvalidator.close();
                if (telemetryReporter != null) telemetryReporter.close();
                if (channel != null) channel.shutdownNow();
                throw e;
            }
        }

        private io.grpc.ManagedChannel buildChannel() {
            io.grpc.ManagedChannelBuilder<?> builder;
            if (targets != null && targets.size() > 1) {
                // Multi-address: custom static resolver
                builder = io.grpc.ManagedChannelBuilder.forTarget("static:///multi")
                        .nameResolverFactory(new StaticNameResolver.Provider(targets));
            } else {
                String t = target != null ? target : targets.getFirst();
                builder = io.grpc.ManagedChannelBuilder.forTarget(t);
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
}
