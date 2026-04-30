package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.event.SdkTypedEvent;
import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.internal.SdkConfig;
import com.authx.sdk.internal.SdkInfrastructure;
import com.authx.sdk.internal.SdkObservability;
import com.authx.sdk.lifecycle.LifecycleManager;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.spi.HealthProbe;
import com.authx.sdk.trace.LogCtx;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * AuthX SDK — Java client for SpiceDB permission management.
 * Connects directly to SpiceDB via gRPC. No platform dependency.
 *
 * <pre>
 * AuthxClient client = AuthxClient.builder()
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
    private final SchemaClient schemaClient;
    private final @Nullable SchemaCache schemaCache;
    private final ConcurrentHashMap<String, DynamicResourceEntry> factories = new ConcurrentHashMap<>();

    /**
     * Legacy constructor — delegates with a null-backed {@link SchemaClient}
     * so existing callers (and the {@link #inMemory()} factory below) do not
     * need to be updated atomically. The builder uses the 7-arg form to
     * pass a populated {@code SchemaClient} and the cache it wraps.
     */
    AuthxClient(SdkTransport transport,
                   SdkInfrastructure infra,
                   SdkObservability observability,
                   SdkConfig config,
                   HealthProbe healthProbe) {
        this(transport, infra, observability, config, healthProbe, null, null);
    }

    AuthxClient(SdkTransport transport,
                   SdkInfrastructure infra,
                   SdkObservability observability,
                   SdkConfig config,
                   HealthProbe healthProbe,
                   SchemaClient schemaClient) {
        this(transport, infra, observability, config, healthProbe, schemaClient, null);
    }

    AuthxClient(SdkTransport transport,
                   SdkInfrastructure infra,
                   SdkObservability observability,
                   SdkConfig config,
                   HealthProbe healthProbe,
                   @Nullable SchemaClient schemaClient,
                   @Nullable SchemaCache schemaCache) {
        this.transport = Objects.requireNonNull(transport);
        this.infra = Objects.requireNonNull(infra);
        this.observability = Objects.requireNonNull(observability);
        this.config = Objects.requireNonNull(config);
        this.healthProbe = Objects.requireNonNull(healthProbe, "healthProbe");
        // Always non-null at the accessor — callers don't need a null check.
        this.schemaClient = schemaClient != null ? schemaClient : new SchemaClient(null);
        // Nullable — only wired when the builder populated it via ReflectSchema.
        // WriteFlow uses this for runtime subject-type validation. When
        // null, validation is a no-op (fail-open).
        this.schemaCache = schemaCache;
    }

    /** Create a new builder for configuring and constructing an {@link AuthxClient}. */
    public static AuthxClientBuilder builder() {
        return new AuthxClientBuilder();
    }

    /** Create an in-memory client for testing (no SpiceDB connection required). */
    public static AuthxClient inMemory() {
        return inMemory(null);
    }

    /**
     * Create an in-memory client with an attached {@link SchemaCache}. Use
     * this when tests / demos need single-type {@code .to(id)} inference,
     * which depends on per-relation subject-type metadata carried by the
     * cache. Pass {@code null} (or call {@link #inMemory()}) for the plain
     * no-schema setup; in that mode a bare-id {@code .to(id)} falls through
     * to {@code SubjectRef.parse} and throws if the id has no {@code ':'}.
     */
    public static AuthxClient inMemory(@Nullable SchemaCache schemaCache) {
        DefaultTypedEventBus bus = new DefaultTypedEventBus();
        LifecycleManager lm = new LifecycleManager(bus);
        lm.begin(); lm.complete();
        SdkInfrastructure infra = new SdkInfrastructure(null, null, Runnable::run, lm);
        SdkObservability observability = new SdkObservability(new SdkMetrics(), bus, null);
        SdkConfig config = new SdkConfig(PolicyRegistry.withDefaults(), false, false);
        return new AuthxClient(new InMemoryTransport(), infra, observability, config,
                HealthProbe.up(), new SchemaClient(schemaCache), schemaCache);
    }

    // ---- Business API ----

    /**
     * String-based overload of the new business API. Prefer generated
     * {@link ResourceType} descriptors when available, but use this dynamic
     * form when the type / relation / permission names are runtime data.
     *
     * <pre>
     * client.on("document").select("doc-1").check("view").by("user:alice");
     * client.on("document").lookupResources("user:alice").can("view");
     * </pre>
     */
    public DynamicResourceEntry on(String resourceType) {
        return dynamic(Objects.requireNonNull(resourceType, "resourceType"));
    }

    private DynamicResourceEntry dynamic(String resourceType) {
        return factories.computeIfAbsent(resourceType, type ->
                new DynamicResourceEntry(type, transport, schemaCache));
    }

    /**
     * Typed entry point — the preferred surface for business code. Hand
     * in the generated descriptor and chain downward (examples assume
     * {@code import static Schema.*}):
     *
     * <pre>
     * client.on(Document).select(docId).check(Document.Perm.VIEW).by(User, userId);
     * client.on(Document).select(docId).grant(Document.Rel.EDITOR).to(User, userId);
     * client.on(Document).select(docId).checkAll().by(User, userId);
     * client.on(Document).lookupResources(User, userId).limit(100).can(Document.Perm.VIEW);
     * </pre>
     *
     * <p>Every operation that used to be spelled as a client-taking
     * static on the generated class (e.g. {@code Document.check(client, ...)})
     * now starts here. The generated class itself is pure type metadata:
     * enums plus the {@link ResourceType} constant.
     */
    public <R extends Enum<R> & Relation.Named,
            P extends Enum<P> & Permission.Named>
    TypedResourceEntry<R, P> on(ResourceType<R, P> resourceType) {
        return new TypedResourceEntry<>(dynamic(resourceType.name()), resourceType);
    }

    /**
     * Start a cross-resource batch builder for atomic operations across
     * multiple resources. Terminal {@code commit()} / {@code execute()} returns
     * {@link WriteCompletion}, matching single-resource write flows.
     */
    public CrossResourceBatchBuilder batch() {
        return new CrossResourceBatchBuilder(transport);
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

    /** Package-private: used by typed API internals to access the transport chain. */
    SdkTransport transport() { return transport; }
    Executor asyncExecutor() { return infra.asyncExecutor(); }

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

    /**
     * Read-only view of the loaded SpiceDB schema. Always non-null; callers
     * should check {@link SchemaClient#isLoaded()} before relying on content
     * (in-memory clients and clients that disabled schema loading both
     * report {@code isLoaded() == false}).
     */
    public SchemaClient schema() {
        return schemaClient;
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
                    LogCtx.fmt(
                            "AuthxClient close step failed: {0} — continuing shutdown. Error: {1}",
                            step, t.toString()));
        }
    }

}
