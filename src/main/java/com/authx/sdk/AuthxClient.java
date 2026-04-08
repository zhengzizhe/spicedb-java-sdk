package com.authx.sdk;

import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.event.SdkTypedEvent;
import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.lifecycle.LifecycleManager;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.RelationshipChange;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * AuthX SDK — Java client for SpiceDB permission management.
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

    /** Create a new builder for configuring and constructing an {@link AuthxClient}. */
    public static AuthxClientBuilder builder() {
        return new AuthxClientBuilder();
    }

    /** Create an in-memory client for testing (no SpiceDB connection required). */
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

    /** Create a one-off resource handle for the given type and id. */
    public ResourceHandle resource(String type, String id) {
        if (caching.schemaCache() != null) caching.schemaCache().validateResourceType(type);
        return new ResourceHandle(type, id, transport, config.defaultSubjectType(), infra.asyncExecutor());
    }

    /** Start a cross-resource lookup query (find all resources a subject can access). */
    public LookupQuery lookup(String resourceType) {
        return new LookupQuery(resourceType, transport, config.defaultSubjectType());
    }

    /** Start a cross-resource batch builder for atomic operations across multiple resources. */
    public CrossResourceBatchBuilder batch() {
        return new CrossResourceBatchBuilder(transport, config.defaultSubjectType());
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

    /** Unsubscribe a previously registered relationship change listener. */
    public void offRelationshipChange(Consumer<RelationshipChange> listener) {
        if (caching.watchDispatcher() != null) {
            caching.watchDispatcher().removeGlobalListener(listener);
        }
    }

    // ---- Observability ----

    /** Package-private: used by TypedResourceFactory to access the transport chain. */
    SdkTransport transport() { return transport; }
    String defaultSubjectType() { return config.defaultSubjectType(); }
    java.util.concurrent.Executor asyncExecutor() { return infra.asyncExecutor(); }

    /** Return the SDK metrics collector. */
    public SdkMetrics metrics() { return observability.metrics(); }

    /** Return the typed event bus for SDK lifecycle and operational events. */
    public TypedEventBus eventBus() { return observability.eventBus(); }

    /** Return the lifecycle manager for inspecting SDK initialization state. */
    public LifecycleManager lifecycle() { return infra.lifecycle(); }

    /** Return the schema client for reading and writing SpiceDB schemas. */
    public SchemaClient schema() { return schemaClient; }

    /** Return the cache handle for manual cache operations (invalidation, stats). */
    public CacheHandle cache() { return caching.handle(); }

    /** Perform a health check against SpiceDB and return latency and reachability status. */
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

}
