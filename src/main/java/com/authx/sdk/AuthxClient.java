package com.authx.sdk;

import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.event.SdkTypedEvent;
import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.internal.SdkConfig;
import com.authx.sdk.internal.SdkInfrastructure;
import com.authx.sdk.internal.SdkObservability;
import com.authx.sdk.lifecycle.LifecycleManager;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.spi.HealthProbe;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AuthX SDK — Java client for SpiceDB permission management.
 * Connects directly to SpiceDB via gRPC. No platform dependency.
 *
 * <pre>
 * var client = AuthxClient.builder()
 *     .connection(c -> c.target("dns:///spicedb.prod:50051").presharedKey("my-key"))
 *     .build();
 * </pre>
 */
public class AuthxClient implements AutoCloseable {

    private final SdkTransport transport;
    private final SdkInfrastructure infra;
    private final SdkObservability observability;
    private final SdkConfig config;
    private final HealthProbe healthProbe;
    private final ConcurrentHashMap<String, ResourceFactory> factories = new ConcurrentHashMap<>();

    AuthxClient(SdkTransport transport,
                   SdkInfrastructure infra,
                   SdkObservability observability,
                   SdkConfig config,
                   HealthProbe healthProbe) {
        this.transport = Objects.requireNonNull(transport);
        this.infra = Objects.requireNonNull(infra);
        this.observability = Objects.requireNonNull(observability);
        this.config = Objects.requireNonNull(config);
        this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe");
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
        var config = new SdkConfig("user", PolicyRegistry.withDefaults(), false, false);
        return new AuthxClient(new InMemoryTransport(), infra, observability, config, HealthProbe.up());
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
        return factories.computeIfAbsent(resourceType, type ->
                new ResourceFactory(type, transport, config.defaultSubjectType(),
                        infra.asyncExecutor()));
    }

    /**
     * Typed entry point — the preferred surface for business code. Hand
     * in the generated {@code Xxx.TYPE} descriptor and chain downward:
     *
     * <pre>
     * client.on(Document.TYPE).select(docId).check(Document.Perm.VIEW).by(userId);
     * client.on(Document.TYPE).select(docId).grant(Document.Rel.EDITOR).to(userId);
     * client.on(Document.TYPE).select(docId).checkAll().by(userId);
     * client.on(Document.TYPE).findByUser(userId).limit(100).can(Document.Perm.VIEW);
     * </pre>
     *
     * <p>Every operation that used to be spelled as a client-taking
     * static on the generated class (e.g. {@code Document.check(client, ...)})
     * now starts here. The generated class itself is pure type metadata:
     * enums plus the {@link ResourceType} constant.
     */
    public <R extends Enum<R> & com.authx.sdk.model.Relation.Named,
            P extends Enum<P> & com.authx.sdk.model.Permission.Named>
    TypedResourceEntry<R, P> on(ResourceType<R, P> resourceType) {
        return new TypedResourceEntry<>(on(resourceType.name()), resourceType);
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
        try {
            var constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            T instance = constructor.newInstance();
            instance.init(resourceType, transport, config.defaultSubjectType(),
                    infra.asyncExecutor());
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to create " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /** Create a one-off resource handle for the given type and id. */
    public ResourceHandle resource(String type, String id) {
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

    /**
     * Start a cross-resource batch permission check. Unlike
     * {@link TypedCheckAction} which is bound to one resource type,
     * {@code batchCheck()} mixes arbitrary
     * (resourceType, id, permission, subject) tuples and sends them all in
     * a single {@code CheckBulkPermissions} RPC, returning a
     * {@link com.authx.sdk.model.CheckMatrix}. Ideal for UI pages that
     * compute multiple unrelated permissions for the same user on one page
     * load (e.g. "can alice view this doc AND complete this task AND edit
     * this folder?" as one round trip).
     */
    public BatchCheckBuilder batchCheck() {
        return new BatchCheckBuilder(transport);
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

    /**
     * Run the configured {@link HealthProbe} and return its result.
     *
     * <p>By default the probe is {@code SchemaReadHealthProbe} (uses
     * {@code SchemaService.ReadSchema} which is schema-independent). Override
     * via {@code SdkComponents.builder().healthProbe(...)}.
     */
    public HealthResult health() {
        return HealthResult.fromProbe(healthProbe.check());
    }

    /** Expose the configured health probe (useful for actuator integration). */
    public HealthProbe healthProbe() {
        return healthProbe;
    }

    // ---- Lifecycle ----

    @Override
    public void close() {
        if (!infra.markClosed()) return; // prevent double-close
        // Partial-failure resilience (F11-7): a throw from any one sub-close
        // must NOT skip the remaining sub-closes. We wrap each step in its
        // own try/catch so that failures are logged at WARN and close()
        // keeps going. close() is typically called from a shutdown hook
        // or try-with-resources where the caller has no meaningful recovery.
        safeClose("eventBus.publish(ClientStopping)",
                () -> observability.eventBus().publish(new SdkTypedEvent.ClientStopping(Instant.now())));
        safeClose("lifecycle.stopping", () -> infra.lifecycle().stopping());

        // Shutdown order: scheduler → telemetry flush → transport → channel → hook
        if (infra.scheduler() != null) {
            safeClose("scheduler.shutdown", () -> {
                infra.scheduler().shutdown();
                try {
                    if (!infra.scheduler().awaitTermination(5, TimeUnit.SECONDS)) {
                        infra.scheduler().shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    infra.scheduler().shutdownNow();
                }
            });
        }
        if (observability.telemetry() != null) {
            safeClose("telemetry.close", observability.telemetry()::close);
        }
        safeClose("transport.close", transport::close);
        safeClose("infra.close", infra::close);

        safeClose("lifecycle.stopped", () -> infra.lifecycle().stopped());
        safeClose("eventBus.publish(ClientStopped)",
                () -> observability.eventBus().publish(new SdkTypedEvent.ClientStopped(Instant.now())));
    }

    /** Run a close step, logging any throw without propagating. */
    private static void safeClose(String step, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            System.getLogger(AuthxClient.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "AuthxClient close step failed: {0} — continuing shutdown. Error: {1}",
                    step, t.toString());
        }
    }

}
