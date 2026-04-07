package com.authx.sdk;

import com.authx.sdk.builtin.ValidationInterceptor;
import com.authx.sdk.cache.Cache;
import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.cache.TieredCache;
import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.event.SdkTypedEvent;
import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.lifecycle.LifecycleManager;
import com.authx.sdk.lifecycle.SdkPhase;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.RelationshipChange;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.spi.SdkComponents;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.telemetry.TelemetryReporter;
import com.authx.sdk.transport.*;
import com.authx.sdk.transport.ResilientTransport;
import com.authx.sdk.watch.WatchDispatcher;
import com.authx.sdk.watch.WatchStrategy;
import com.github.benmanes.caffeine.cache.Expiry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * AuthCSES SDK — Java client for SpiceDB permission management.
 * Connects directly to SpiceDB via gRPC. No platform dependency.
 *
 * <pre>
 * var client = AuthxClient.builder()
 *     .connection(c -> c.target("dns:///spicedb.prod:50051").presharedKey("my-key"))
 *     .cache(c -> c.enabled(true))
 *     .build();
 * </pre>
 */
public class AuthxClient implements AutoCloseable {

    private final SdkTransport transport;
    private final SdkInfrastructure infra;
    private final SdkObservability observability;
    private final SdkCaching caching;
    private final SdkConfig config;
    private final SchemaClient schemaClient;
    private final ConcurrentHashMap<String, ResourceFactory> factories = new ConcurrentHashMap<>();

    AuthxClient(SdkTransport transport,
                   SdkInfrastructure infra,
                   SdkObservability observability,
                   SdkCaching caching,
                   SdkConfig config) {
        this.transport = Objects.requireNonNull(transport);
        this.infra = Objects.requireNonNull(infra);
        this.observability = Objects.requireNonNull(observability);
        this.caching = Objects.requireNonNull(caching);
        this.config = Objects.requireNonNull(config);
        this.schemaClient = new SchemaClient(caching.schemaCache());

        // Wire dispatcher into Watch stream
        if (caching.watchInvalidator() != null && caching.watchDispatcher() != null) {
            caching.watchInvalidator().addListener(caching.watchDispatcher());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AuthxClient inMemory() {
        var bus = new DefaultTypedEventBus();
        var lm = new LifecycleManager(bus);
        lm.begin(); lm.complete();
        var infra = new SdkInfrastructure(null, null, Runnable::run, lm);
        var observability = new SdkObservability(new SdkMetrics(), bus, null);
        var caching = new SdkCaching(null, null, null, null);
        var config = new SdkConfig("user", PolicyRegistry.withDefaults(), false, false);
        return new AuthxClient(new InMemoryTransport(), infra, observability, caching, config);
    }

    // ---- Business API ----

    /**
     * Get a cached factory for a resource type. Auto-created on first call.
     *
     * <pre>
     * // Chain style
     * client.on("document").resource("doc-1").check("view").by("alice");
     *
     * // Store factory, reuse
     * var doc = client.on("document");
     * doc.resource("doc-1").check("view").by("alice");
     * doc.resource("doc-2").grant("editor").to("bob");
     *
     * // Simple style (no chaining needed)
     * doc.check("doc-1", "view", "alice");
     * doc.grant("doc-1", "editor", "bob");
     * </pre>
     */
    public ResourceFactory on(String resourceType) {
        return factories.computeIfAbsent(resourceType, type -> {
            if (caching.schemaCache() != null) caching.schemaCache().validateResourceType(type);
            return new ResourceFactory(type, transport, config.defaultSubjectType(), infra.asyncExecutor());
        });
    }

    /**
     * Create a typed permission service from a {@link PermissionResource} annotated class.
     *
     * <pre>
     * @PermissionResource("document")
     * public class DocumentPermission extends ResourceFactory {
     *     public boolean canView(String docId, String userId) {
     *         return check(docId, "view", userId);
     *     }
     * }
     *
     * @Bean DocumentPermission doc(AuthxClient c) { return c.create(DocumentPermission.class); }
     * </pre>
     */
    public <T extends ResourceFactory> T create(Class<T> clazz) {
        var annotation = clazz.getAnnotation(PermissionResource.class);
        if (annotation == null) {
            throw new IllegalArgumentException(clazz.getSimpleName() + " must be annotated with @PermissionResource");
        }
        String resourceType = annotation.value();
        if (caching.schemaCache() != null) caching.schemaCache().validateResourceType(resourceType);
        try {
            var constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            T instance = constructor.newInstance();
            instance.init(resourceType, transport, config.defaultSubjectType(), infra.asyncExecutor());
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to create " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * One-off resource handle.
     */
    public ResourceHandle resource(String type, String id) {
        if (caching.schemaCache() != null) caching.schemaCache().validateResourceType(type);
        return new ResourceHandle(type, id, transport, config.defaultSubjectType(), infra.asyncExecutor());
    }

    public LookupQuery lookup(String resourceType) {
        return new LookupQuery(resourceType, transport, config.defaultSubjectType());
    }

    public CrossResourceBatchBuilder batch() {
        return new CrossResourceBatchBuilder(transport, config.defaultSubjectType());
    }

    // ---- Convenience methods (type as first param) ----
    // For full variants (consistency, caveat, Collection overloads), use on(type).xxx()

    // -- Check --

    /** Check a single permission. Returns true if allowed. Default: minimize_latency. */
    public boolean check(String type, String id, String permission, String userId) {
        return on(type).check(id, permission, userId);
    }

    /** Check with explicit consistency. */
    public boolean check(String type, String id, String permission, String userId,
                         Consistency consistency) {
        return on(type).check(id, permission, userId, consistency);
    }

    /** Check returning full result. */
    public CheckResult checkResult(String type, String id, String permission, String userId) {
        return on(type).checkResult(id, permission, userId);
    }

    /** Check multiple permissions at once. Returns map of permission→boolean. */
    public Map<String, Boolean> checkAll(String type, String id, String userId, String... permissions) {
        return on(type).checkAll(id, userId, permissions);
    }

    // -- Grant --

    /** Grant relation to user(s). */
    public void grant(String type, String id, String relation, String... userIds) {
        on(type).grant(id, relation, userIds);
    }

    /** Grant relation to subject refs (e.g., "department:eng#member", "user:*"). */
    public void grantToSubjects(String type, String id, String relation, String... subjectRefs) {
        on(type).grantToSubjects(id, relation, subjectRefs);
    }

    // -- Revoke --

    /** Revoke relation from user(s). */
    public void revoke(String type, String id, String relation, String... userIds) {
        on(type).revoke(id, relation, userIds);
    }

    /** Revoke relation from subject refs. */
    public void revokeFromSubjects(String type, String id, String relation, String... subjectRefs) {
        on(type).revokeFromSubjects(id, relation, subjectRefs);
    }

    /** Remove all relations for user(s) on this resource. */
    public void revokeAll(String type, String id, String... userIds) {
        on(type).revokeAll(id, userIds);
    }

    // ---- Watch (real-time relationship change events) ----

    /**
     * Subscribe to ALL relationship changes (cross-cutting: audit, logging).
     * For per-type handling, use {@link WatchStrategy} instead.
     *
     * @throws IllegalStateException if Watch is not enabled
     */
    public void onRelationshipChange(Consumer<RelationshipChange> listener) {
        if (caching.watchDispatcher() == null) {
            throw new IllegalStateException(
                    "Watch not enabled. Use .cache(c -> c.enabled(true).watchInvalidation(true)) in Builder.");
        }
        caching.watchDispatcher().addGlobalListener(listener);
    }

    public void offRelationshipChange(Consumer<RelationshipChange> listener) {
        if (caching.watchDispatcher() != null) {
            caching.watchDispatcher().removeGlobalListener(listener);
        }
    }

    // ---- Observability ----

    public SdkMetrics metrics() { return observability.metrics(); }
    public TypedEventBus eventBus() { return observability.eventBus(); }
    public LifecycleManager lifecycle() { return infra.lifecycle(); }
    public SchemaClient schema() { return schemaClient; }
    public CacheHandle cache() { return caching.handle(); }

    public HealthResult health() {
        long start = System.nanoTime();
        try {
            transport.readRelationships(
                    ResourceRef.of("healthprobe", "probe"),
                    null,
                    Consistency.minimizeLatency());
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
        if (!infra.markClosed()) return; // prevent double-close
        observability.eventBus().publish(new SdkTypedEvent.ClientStopping(Instant.now()));
        infra.lifecycle().stopping();

        // Shutdown order: scheduler → watch → telemetry flush → transport → channel → hook
        // Close scheduler first (stops periodic tasks), but keep channel alive for watch/transport
        if (infra.scheduler() != null) {
            infra.scheduler().shutdown();
            try { infra.scheduler().awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (caching.watchInvalidator() != null) caching.watchInvalidator().close();
        if (observability.telemetry() != null) observability.telemetry().close();
        transport.close();
        infra.close(); // channel shutdown + hook removal (scheduler already stopped, will no-op)

        infra.lifecycle().stopped();
        observability.eventBus().publish(new SdkTypedEvent.ClientStopped(Instant.now()));
    }

    // ============================================================
    //  Builder
    // ============================================================

    /**
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
        private TypedEventBus eventBus;
        private SdkComponents components;
        private final List<SdkInterceptor> interceptors = new ArrayList<>();
        private final List<WatchStrategy> watchStrategies = new ArrayList<>();

        // ============================================================
        //  Grouped configuration (lambda style)
        // ============================================================

        /** Connection settings. */
        public Builder connection(Consumer<ConnectionConfig> config) {
            config.accept(new ConnectionConfig());
            return this;
        }

        /** Cache settings. */
        public Builder cache(Consumer<CacheConfig> config) {
            config.accept(new CacheConfig());
            return this;
        }

        /** Feature toggles. */
        public Builder features(Consumer<FeatureConfig> config) {
            config.accept(new FeatureConfig());
            return this;
        }

        /** Extensibility (policies, SPI, interceptors). */
        public Builder extend(Consumer<ExtendConfig> config) {
            config.accept(new ExtendConfig());
            return this;
        }

        public class ConnectionConfig {
            public ConnectionConfig target(String t) { Builder.this.target = t; return this; }
            public ConnectionConfig targets(String... t) { Builder.this.targets = List.of(t); return this; }
            public ConnectionConfig presharedKey(String k) { Builder.this.presharedKey = k; return this; }
            public ConnectionConfig tls(boolean t) { Builder.this.useTls = t; return this; }
            public ConnectionConfig loadBalancing(String p) { Builder.this.loadBalancing = p; return this; }
            public ConnectionConfig keepAliveTime(Duration d) { Builder.this.keepAliveTime = d; return this; }
            public ConnectionConfig requestTimeout(Duration d) { Builder.this.requestTimeout = d; return this; }
        }

        public class CacheConfig {
            public CacheConfig enabled(boolean e) { Builder.this.cacheEnabled = e; return this; }
            public CacheConfig maxSize(long s) { Builder.this.cacheMaxSize = s; return this; }
            public CacheConfig watchInvalidation(boolean e) { Builder.this.watchInvalidation = e; return this; }
        }

        public class FeatureConfig {
            public FeatureConfig coalescing(boolean e) { Builder.this.coalescingEnabled = e; return this; }
            public FeatureConfig virtualThreads(boolean e) { Builder.this.useVirtualThreads = e; return this; }
            public FeatureConfig shutdownHook(boolean e) { Builder.this.registerShutdownHook = e; return this; }
            public FeatureConfig telemetry(boolean e) { Builder.this.telemetryEnabled = e; return this; }
            public FeatureConfig defaultSubjectType(String t) { Builder.this.defaultSubjectType = t; return this; }
        }

        public class ExtendConfig {
            public ExtendConfig policies(PolicyRegistry p) { Builder.this.policyRegistry = p; return this; }
            public ExtendConfig eventBus(TypedEventBus b) { Builder.this.eventBus = b; return this; }
            public ExtendConfig components(SdkComponents c) { Builder.this.components = c; return this; }
            public ExtendConfig addInterceptor(SdkInterceptor i) { Builder.this.interceptors.add(i); return this; }
            /** Register a Watch strategy for a resource type. Requires watchInvalidation(true). */
            public ExtendConfig addWatchStrategy(WatchStrategy s) { Builder.this.watchStrategies.add(s); return this; }
        }

        /** Mutable holder for intermediate build artifacts. Replaces single-element array hacks. */
        private static class BuildContext {
            Cache<CheckKey, CheckResult> checkCache;
            TelemetryReporter telemetryReporter;
            ResilientTransport resilientTransport;
            ScheduledExecutorService scheduler;
            WatchCacheInvalidator watchInvalidator;
        }

        public AuthxClient build() {
            Objects.requireNonNull(presharedKey, "presharedKey is required");
            if (target == null && targets == null) throw new IllegalArgumentException("target or targets is required");

            var policies = policyRegistry != null ? policyRegistry : PolicyRegistry.withDefaults();
            var spi = components != null ? components : SdkComponents.defaults();
            var bus = eventBus != null ? eventBus : new DefaultTypedEventBus();
            var lm = new LifecycleManager(bus);
            var sdkMetrics = new SdkMetrics();
            var schemaCache = new SchemaCache();
            var schemaLoader = new SchemaLoader();
            var tokenTracker = new TokenTracker(spi.tokenStore());

            // Built-in interceptors
            interceptors.addFirst(new ValidationInterceptor());

            ManagedChannel channel = null;
            WatchCacheInvalidator watchInvalidator = null;
            TelemetryReporter telemetryReporter = null;
            ScheduledExecutorService scheduler = null;

            try {
                lm.begin();

                // Phase: CHANNEL — single HTTP/2 connection with multiplexing
                // gRPC + HTTP/2 supports thousands of concurrent streams per connection.
                // With DNS + round_robin, gRPC auto-creates subchannels to each backend.
                final ManagedChannel grpcChannel = lm.phase(
                        SdkPhase.CHANNEL, () -> buildChannel());
                channel = grpcChannel;

                // Phase: SCHEMA
                Metadata authMeta = new Metadata();
                authMeta.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + presharedKey);
                final Metadata authMetaFinal = authMeta;
                lm.phase(SdkPhase.SCHEMA, () ->
                        schemaLoader.load(grpcChannel, authMetaFinal, schemaCache));
                // Enable on-miss refresh for schema validation
                schemaCache.setRefreshCallback(() ->
                        schemaLoader.load(grpcChannel, authMetaFinal, schemaCache));

                // Phase: TRANSPORT
                final var ctx = new BuildContext();
                SdkTransport transport = lm.phase(SdkPhase.TRANSPORT, () -> {
                    SdkTransport t = new GrpcTransport(grpcChannel, presharedKey, requestTimeout.toMillis());

                    // Resilience (circuit breaker + retry via Resilience4j)
                    var resilientTransport = new ResilientTransport(t, policies, bus);
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
                            effectiveCache = spi.l2Cache() != null
                                    ? new TieredCache<>(l1, spi.l2Cache())
                                    : l1;
                        } catch (NoClassDefFoundError e) {
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
                    if (!interceptors.isEmpty()) t = new InterceptorTransport(t, interceptors);
                    return t;
                });
                telemetryReporter = ctx.telemetryReporter;
                if (ctx.resilientTransport != null) {
                    sdkMetrics.setCircuitBreakerStateSupplier(
                            () -> ctx.resilientTransport.getCircuitBreakerState("_default").name());
                }

                // Phase: WATCH
                lm.phase(SdkPhase.WATCH, () -> {
                    if (cacheEnabled && watchInvalidation && ctx.checkCache != null) {
                        ctx.watchInvalidator = new WatchCacheInvalidator(
                                grpcChannel, presharedKey, ctx.checkCache, sdkMetrics);
                        ctx.watchInvalidator.start();
                    }
                });
                watchInvalidator = ctx.watchInvalidator;

                // Phase: SCHEDULER (schema refresh)
                lm.phase(SdkPhase.SCHEDULER, () -> {
                    ThreadFactory tf = useVirtualThreads
                            ? Thread.ofVirtual().name("authx-sdk-", 0).factory()
                            : r -> { Thread th = new Thread(r, "authx-sdk-refresh"); th.setDaemon(true); return th; };
                    ctx.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
                    // Refresh schema every 5 minutes
                    ctx.scheduler.scheduleAtFixedRate(
                            () -> schemaLoader.load(grpcChannel, authMetaFinal, schemaCache),
                            300, 300, TimeUnit.SECONDS);
                    // Rotate metrics histogram every 5 seconds (single consumer, no contention)
                    ctx.scheduler.scheduleAtFixedRate(sdkMetrics::rotateHistogram, 5, 5, TimeUnit.SECONDS);
                    // Sample cache size periodically (not on hot path)
                    if (cacheEnabled && ctx.checkCache != null) {
                        var cache = ctx.checkCache;
                        ctx.scheduler.scheduleAtFixedRate(
                                () -> sdkMetrics.updateCacheSize(cache.size()), 5, 5, TimeUnit.SECONDS);
                    }
                });
                scheduler = ctx.scheduler;

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
                        : watchInvalidator != null ? new WatchDispatcher(List.of()) : null;

                // Async executor: virtual threads if enabled, otherwise direct (caller thread)
                Executor asyncExec = useVirtualThreads
                        ? Executors.newVirtualThreadPerTaskExecutor()
                        : Runnable::run;

                // Build aggregation objects
                var infraObj = new SdkInfrastructure(grpcChannel, scheduler, asyncExec, lm);
                var observabilityObj = new SdkObservability(sdkMetrics, bus, telemetryReporter);
                var cachingObj = new SdkCaching(ctx.checkCache, schemaCache, watchInvalidator, dispatcher);
                var configObj = new SdkConfig(defaultSubjectType, policies, coalescingEnabled, useVirtualThreads);

                var client = new AuthxClient(transport, infraObj, observabilityObj, cachingObj, configObj);

                if (registerShutdownHook) {
                    var hook = new Thread(client::close, "authx-sdk-shutdown");
                    Runtime.getRuntime().addShutdownHook(hook);
                    infraObj.setShutdownHook(hook);
                }

                return client;
            } catch (Exception e) {
                if (scheduler != null) {
                    scheduler.shutdown();
                    try { scheduler.awaitTermination(1, TimeUnit.SECONDS); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                if (watchInvalidator != null) watchInvalidator.close();
                if (telemetryReporter != null) telemetryReporter.close();
                if (channel != null) {
                    channel.shutdown();
                    try { channel.awaitTermination(1, TimeUnit.SECONDS); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                throw e;
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
}
