package com.authcses.sdk;

import com.authcses.sdk.cache.*;
import com.authcses.sdk.policy.PolicyRegistry;
import com.authcses.sdk.telemetry.TelemetryReporter;
import com.authcses.sdk.transport.*;
import com.authcses.sdk.transport.ResilientTransport;

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
    private final com.authcses.sdk.watch.WatchDispatcher watchDispatcher;
    private final java.util.concurrent.Executor asyncExecutor;

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
                   String defaultSubjectType,
                   com.authcses.sdk.watch.WatchDispatcher watchDispatcher,
                   java.util.concurrent.Executor asyncExecutor) {
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
        this.watchDispatcher = watchDispatcher;
        this.asyncExecutor = asyncExecutor != null ? asyncExecutor : Runnable::run;

        // Wire dispatcher into Watch stream
        if (watchInvalidator != null && watchDispatcher != null) {
            watchInvalidator.addListener(watchDispatcher);
        }
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
                null, null, null, "user", null, null);
    }

    // ---- Business API ----

    private final java.util.concurrent.ConcurrentHashMap<String, ResourceFactory> factories = new java.util.concurrent.ConcurrentHashMap<>();

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
            if (schemaCache != null) schemaCache.validateResourceType(type);
            return new ResourceFactory(type, transport, defaultSubjectType, asyncExecutor);
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
     * @Bean DocumentPermission doc(AuthCsesClient c) { return c.create(DocumentPermission.class); }
     * </pre>
     */
    public <T extends ResourceFactory> T create(Class<T> clazz) {
        var annotation = clazz.getAnnotation(PermissionResource.class);
        if (annotation == null) {
            throw new IllegalArgumentException(clazz.getSimpleName() + " must be annotated with @PermissionResource");
        }
        String resourceType = annotation.value();
        if (schemaCache != null) schemaCache.validateResourceType(resourceType);
        try {
            var constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            T instance = constructor.newInstance();
            instance.init(resourceType, transport, defaultSubjectType, asyncExecutor);
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to create " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * One-off resource handle.
     */
    public ResourceHandle resource(String type, String id) {
        if (schemaCache != null) schemaCache.validateResourceType(type);
        return new ResourceHandle(type, id, transport, defaultSubjectType, asyncExecutor);
    }

    public LookupQuery lookup(String resourceType) {
        return new LookupQuery(resourceType, transport, defaultSubjectType);
    }

    public CrossResourceBatchBuilder batch() {
        return new CrossResourceBatchBuilder(transport, defaultSubjectType);
    }

    // ---- Convenience methods (type as first param) ----
    // For full variants (consistency, caveat, Collection overloads), use on(type).xxx()

    // -- Check --

    /** Check a single permission. Returns true if allowed. Default: minimize_latency. */
    public boolean check(String type, String id, String permission, String userId) {
        // L0 fast path: direct cache lookup, skip entire transport chain
        if (checkCache != null) {
            var cached = checkCache.getIfPresent(type, id, permission, defaultSubjectType, userId);
            if (cached != null) return cached.hasPermission();
        }
        return on(type).check(id, permission, userId);
    }

    /** Check with explicit consistency. */
    public boolean check(String type, String id, String permission, String userId,
                         com.authcses.sdk.model.Consistency consistency) {
        // L0 fast path: only for MinimizeLatency (cacheable)
        if (consistency instanceof com.authcses.sdk.model.Consistency.MinimizeLatency && checkCache != null) {
            var cached = checkCache.getIfPresent(type, id, permission, defaultSubjectType, userId);
            if (cached != null) return cached.hasPermission();
        }
        return on(type).check(id, permission, userId, consistency);
    }

    /** Check returning full result. */
    public com.authcses.sdk.model.CheckResult checkResult(String type, String id, String permission, String userId) {
        return on(type).checkResult(id, permission, userId);
    }

    /** Check multiple permissions at once. Returns map of permission→boolean. */
    public java.util.Map<String, Boolean> checkAll(String type, String id, String userId, String... permissions) {
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
     * For per-type handling, use {@link com.authcses.sdk.watch.WatchStrategy} instead.
     *
     * @throws IllegalStateException if Watch is not enabled
     */
    public void onRelationshipChange(java.util.function.Consumer<com.authcses.sdk.model.RelationshipChange> listener) {
        if (watchDispatcher == null) {
            throw new IllegalStateException(
                    "Watch not enabled. Use .cache(c -> c.enabled(true).watchInvalidation(true)) in Builder.");
        }
        watchDispatcher.addGlobalListener(listener);
    }

    public void offRelationshipChange(java.util.function.Consumer<com.authcses.sdk.model.RelationshipChange> listener) {
        if (watchDispatcher != null) {
            watchDispatcher.removeGlobalListener(listener);
        }
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
            transport.readRelationships(
                    com.authcses.sdk.model.ResourceRef.of("healthprobe", "probe"),
                    null,
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
            try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (watchInvalidator != null) watchInvalidator.close();
        if (telemetryReporter != null) telemetryReporter.close();
        transport.close();
        if (grpcChannel != null) {
            grpcChannel.shutdown();
            try { grpcChannel.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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

    /**
     * <pre>
     * // Grouped style (recommended)
     * AuthCsesClient.builder()
     *     .connection(c -> c
     *         .target("dns:///spicedb.prod:50051")
     *         .presharedKey("my-key")
     *         .tls(true))
     *     .cache(c -> c
     *         .enabled(true)
     *         .maxSize(100_000)
     *         .watchInvalidation(true))
     *     .build();
     *
     * // Flat style (also works)
     * AuthCsesClient.builder()
     *     .target("localhost:50051")
     *     .presharedKey("my-key")
     *     .cacheEnabled(true)
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
        private com.authcses.sdk.event.SdkEventBus eventBus;
        private com.authcses.sdk.spi.SdkComponents components;
        private final java.util.List<com.authcses.sdk.spi.SdkInterceptor> interceptors = new java.util.ArrayList<>();
        private final java.util.List<com.authcses.sdk.watch.WatchStrategy> watchStrategies = new java.util.ArrayList<>();

        // ============================================================
        //  Grouped configuration (lambda style)
        // ============================================================

        /** Connection settings. */
        public Builder connection(java.util.function.Consumer<ConnectionConfig> config) {
            config.accept(new ConnectionConfig());
            return this;
        }

        /** Cache settings. */
        public Builder cache(java.util.function.Consumer<CacheConfig> config) {
            config.accept(new CacheConfig());
            return this;
        }

        /** Feature toggles. */
        public Builder features(java.util.function.Consumer<FeatureConfig> config) {
            config.accept(new FeatureConfig());
            return this;
        }

        /** Extensibility (policies, SPI, interceptors). */
        public Builder extend(java.util.function.Consumer<ExtendConfig> config) {
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
            public ExtendConfig eventBus(com.authcses.sdk.event.SdkEventBus b) { Builder.this.eventBus = b; return this; }
            public ExtendConfig components(com.authcses.sdk.spi.SdkComponents c) { Builder.this.components = c; return this; }
            public ExtendConfig addInterceptor(com.authcses.sdk.spi.SdkInterceptor i) { Builder.this.interceptors.add(i); return this; }
            /** Register a Watch strategy for a resource type. Requires watchInvalidation(true). */
            public ExtendConfig addWatchStrategy(com.authcses.sdk.watch.WatchStrategy s) { Builder.this.watchStrategies.add(s); return this; }
        }

        // ============================================================
        //  Flat setters (backward compatible)
        // ============================================================

        public Builder target(String target) { this.target = target; return this; }
        public Builder targets(String... targets) { this.targets = List.of(targets); return this; }
        public Builder presharedKey(String key) { this.presharedKey = key; return this; }
        public Builder useTls(boolean tls) { this.useTls = tls; return this; }
        public Builder loadBalancing(String policy) { this.loadBalancing = policy; return this; }
        public Builder keepAliveTime(Duration d) { this.keepAliveTime = d; return this; }
        public Builder requestTimeout(Duration d) { this.requestTimeout = d; return this; }
        public Builder cacheEnabled(boolean e) { this.cacheEnabled = e; return this; }
        public Builder cacheMaxSize(long s) { this.cacheMaxSize = s; return this; }
        public Builder watchInvalidation(boolean e) { this.watchInvalidation = e; return this; }
        public Builder coalescingEnabled(boolean e) { this.coalescingEnabled = e; return this; }
        public Builder useVirtualThreads(boolean e) { this.useVirtualThreads = e; return this; }
        public Builder registerShutdownHook(boolean e) { this.registerShutdownHook = e; return this; }
        public Builder telemetryEnabled(boolean e) { this.telemetryEnabled = e; return this; }
        public Builder defaultSubjectType(String t) { this.defaultSubjectType = t; return this; }
        public Builder policies(PolicyRegistry p) { this.policyRegistry = p; return this; }
        public Builder eventBus(com.authcses.sdk.event.SdkEventBus b) { this.eventBus = b; return this; }
        public Builder components(com.authcses.sdk.spi.SdkComponents c) { this.components = c; return this; }
        public Builder addInterceptor(com.authcses.sdk.spi.SdkInterceptor i) { this.interceptors.add(i); return this; }
        public Builder addWatchStrategy(com.authcses.sdk.watch.WatchStrategy s) { this.watchStrategies.add(s); return this; }

        public AuthCsesClient build() {
            Objects.requireNonNull(presharedKey, "presharedKey is required");
            if (target == null && targets == null) throw new IllegalArgumentException("target or targets is required");

            var policies = policyRegistry != null ? policyRegistry : PolicyRegistry.withDefaults();
            var spi = components != null ? components : com.authcses.sdk.spi.SdkComponents.defaults();
            var bus = eventBus != null ? eventBus : new com.authcses.sdk.event.SdkEventBus();
            var lm = new com.authcses.sdk.lifecycle.LifecycleManager(bus);
            var sdkMetrics = new com.authcses.sdk.metrics.SdkMetrics();
            var schemaCache = new SchemaCache();
            var tokenTracker = new TokenTracker(spi.tokenStore());

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
                // Enable on-miss refresh for schema validation
                schemaCache.setRefreshCallback(() ->
                        SchemaLoader.load(grpcChannel, authMetaFinal, schemaCache));

                // Phase: TRANSPORT
                final CheckCache[] cacheHolder = {null};
                final TelemetryReporter[] telemetryHolder = {null};
                final ResilientTransport[] resilientHolder = {null};
                SdkTransport transport = lm.phase(com.authcses.sdk.lifecycle.SdkPhase.TRANSPORT, () -> {
                    SdkTransport t = new GrpcTransport(grpcChannel, presharedKey, requestTimeout.toMillis());

                    // Resilience (circuit breaker + retry via Resilience4j)
                    var resilientTransport = new ResilientTransport(t, policies, bus);
                    resilientHolder[0] = resilientTransport;
                    t = resilientTransport;

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
                if (resilientHolder[0] != null) {
                    sdkMetrics.setCircuitBreakerStateSupplier(
                            () -> resilientHolder[0].getCircuitBreakerState("_default").name());
                }

                // Phase: WATCH
                final WatchCacheInvalidator[] watchHolder = {null};
                lm.phase(com.authcses.sdk.lifecycle.SdkPhase.WATCH, () -> {
                    if (cacheEnabled && watchInvalidation && cacheHolder[0] != null) {
                        watchHolder[0] = new WatchCacheInvalidator(
                                grpcChannel, presharedKey, cacheHolder[0]);
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

                // Warn: SESSION consistency without distributed token store
                if (spi.tokenStore() == null) {
                    System.getLogger(AuthCsesClient.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "No DistributedTokenStore configured — SESSION consistency only works " +
                            "within a single JVM. For multi-instance deployments, provide a Redis-backed " +
                            "tokenStore via .extend(e -> e.components(SdkComponents.builder()" +
                            ".tokenStore(redisStore).build()))");
                }

                // Build Watch dispatcher (strategies → dispatcher → watchInvalidator)
                var dispatcher = !watchStrategies.isEmpty()
                        ? new com.authcses.sdk.watch.WatchDispatcher(watchStrategies)
                        : watchInvalidator != null ? new com.authcses.sdk.watch.WatchDispatcher(List.of()) : null;

                // Async executor: virtual threads if enabled, otherwise direct (caller thread)
                java.util.concurrent.Executor asyncExec = useVirtualThreads
                        ? java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
                        : Runnable::run;

                var client = new AuthCsesClient(transport, grpcChannel, schemaCache, cacheHolder[0], sdkMetrics,
                        bus, lm, watchInvalidator, telemetryReporter, scheduler, defaultSubjectType, dispatcher,
                        asyncExec);

                if (registerShutdownHook) {
                    var hook = new Thread(client::close, "authcses-sdk-shutdown");
                    Runtime.getRuntime().addShutdownHook(hook);
                    client.shutdownHookRef = hook;
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
