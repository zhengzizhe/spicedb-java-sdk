package com.authx.sdk;

import com.authx.sdk.builtin.ValidationInterceptor;
import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.internal.SdkConfig;
import com.authx.sdk.internal.SdkInfrastructure;
import com.authx.sdk.internal.SdkObservability;
import com.authx.sdk.lifecycle.LifecycleManager;
import com.authx.sdk.lifecycle.SdkPhase;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.health.ChannelStateHealthProbe;
import com.authx.sdk.health.SchemaReadHealthProbe;
import com.authx.sdk.spi.HealthProbe;
import com.authx.sdk.spi.SdkComponents;
import com.authx.sdk.spi.SdkInterceptor;
import com.authx.sdk.telemetry.TelemetryReporter;
import com.authx.sdk.transport.CoalescingTransport;
import com.authx.sdk.transport.GrpcTransport;
import com.authx.sdk.transport.InstrumentedTransport;
import com.authx.sdk.transport.InterceptorTransport;
import com.authx.sdk.transport.PolicyAwareConsistencyTransport;
import com.authx.sdk.transport.ResilientTransport;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.StaticNameResolver;
import com.authx.sdk.transport.TokenTracker;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

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
 *     .features(f -> f.coalescing(true).telemetry(true))
 *     .build();
 * </pre>
 *
 * <p><b>Note on cache removal</b> — as of ADR 2026-04-18 the SDK no
 * longer has a client-side decision cache or Watch stream. `check()`
 * always goes to SpiceDB, whose server-side dispatch cache handles
 * decision-level caching correctly (schema-aware, no inheritance
 * invalidation gap). Use {@code Consistency.minimizeLatency()} for the
 * lowest-latency reads.
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

    // Features
    private boolean coalescingEnabled = true;
    private boolean useVirtualThreads = false;
    private boolean registerShutdownHook = false;
    private boolean telemetryEnabled = false;

    // Extensibility
    private PolicyRegistry policyRegistry;
    private TypedEventBus eventBus;
    private SdkComponents components;
    private final List<SdkInterceptor> interceptors = new ArrayList<>();
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
     * to separate business policy decisions (consistency, retry
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

    public class FeatureConfig {
        public FeatureConfig coalescing(boolean e) { AuthxClientBuilder.this.coalescingEnabled = e; return this; }
        public FeatureConfig virtualThreads(boolean e) { AuthxClientBuilder.this.useVirtualThreads = e; return this; }
        public FeatureConfig shutdownHook(boolean e) { AuthxClientBuilder.this.registerShutdownHook = e; return this; }
        public FeatureConfig telemetry(boolean e) { AuthxClientBuilder.this.telemetryEnabled = e; return this; }
    }

    public class ExtendConfig {
        public ExtendConfig policies(PolicyRegistry p) { AuthxClientBuilder.this.policyRegistry = p; return this; }
        public ExtendConfig eventBus(TypedEventBus b) { AuthxClientBuilder.this.eventBus = b; return this; }
        public ExtendConfig components(SdkComponents c) { AuthxClientBuilder.this.components = c; return this; }
        public ExtendConfig addInterceptor(SdkInterceptor i) { AuthxClientBuilder.this.interceptors.add(i); return this; }
    }

    /** Mutable holder for intermediate build artifacts. Replaces single-element array hacks. */
    private static class BuildContext {
        TelemetryReporter telemetryReporter;
        ResilientTransport resilientTransport;
        ScheduledExecutorService scheduler;
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
            PolicyRegistry.Builder pBuilder = PolicyRegistry.builder();
            for (var customizer : policyCustomizers) {
                customizer.customize(pBuilder);
            }
            policies = pBuilder.build();
        } else {
            policies = PolicyRegistry.withDefaults();
        }
        var spi = components != null ? components : SdkComponents.defaults();
        var bus = eventBus != null ? eventBus : new DefaultTypedEventBus();
        var lm = new LifecycleManager(bus);
        var sdkMetrics = new SdkMetrics();
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
        // constructed resources (scheduler, telemetry reporter) regardless
        // of which phase threw.
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

            // Phase: TRANSPORT
            SdkTransport transport = lm.phase(SdkPhase.TRANSPORT, () ->
                    buildTransportStack(grpcChannel, policies, spi, bus, sdkMetrics,
                            tokenTracker, effectiveInterceptors, ctx));
            if (ctx.resilientTransport != null) {
                sdkMetrics.setCircuitBreakerStateSupplier(
                        () -> ctx.resilientTransport.getCircuitBreakerState("_default").name());
            }

            // Phase: SCHEDULER
            lm.phase(SdkPhase.SCHEDULER, () ->
                    buildScheduler(sdkMetrics, ctx));

            lm.complete();

            // Warn: SESSION consistency without distributed token store
            if (spi.tokenStore() == null) {
                System.getLogger(AuthxClient.class.getName()).log(
                        System.Logger.Level.WARNING,
                        com.authx.sdk.trace.LogCtx.fmt(
                                "No DistributedTokenStore configured — SESSION consistency only works " +
                                "within a single JVM. For multi-instance deployments, provide a Redis-backed " +
                                "tokenStore via .extend(e -> e.components(SdkComponents.builder()" +
                                ".tokenStore(redisStore).build()))"));
            }

            // Async executor: virtual threads if enabled, otherwise direct (caller thread)
            Executor asyncExec = useVirtualThreads
                    ? Executors.newVirtualThreadPerTaskExecutor()
                    : Runnable::run;

            // Build aggregation objects
            var infraObj = new SdkInfrastructure(grpcChannel, ctx.scheduler, asyncExec, lm);
            var observabilityObj = new SdkObservability(sdkMetrics, bus, ctx.telemetryReporter);
            var configObj = new SdkConfig(policies, coalescingEnabled, useVirtualThreads);

            // Resolve health probe: user-provided takes precedence, otherwise
            // default to a composite of channel-state + schema-read so diagnostics
            // show both the local channel health and end-to-end SpiceDB reachability.
            HealthProbe probe = spi.healthProbe();
            if (probe == null) {
                probe = HealthProbe.all(
                        new ChannelStateHealthProbe(grpcChannel),
                        new SchemaReadHealthProbe(grpcChannel, presharedKey));
            }

            var client = new AuthxClient(transport, infraObj, observabilityObj, configObj, probe);

            if (registerShutdownHook) {
                var hook = new Thread(client::close, "authx-sdk-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                infraObj.setShutdownHook(hook);
            }

            return client;
        } catch (Exception e) {
            // Unwind partially-constructed resources. ctx is visible here
            // because it was hoisted above the try block.
            if (ctx.scheduler != null) {
                ctx.scheduler.shutdown();
                try { ctx.scheduler.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
     *   GrpcTransport → ResilientTransport → InstrumentedTransport
     *   → PolicyAwareConsistencyTransport → CoalescingTransport → InterceptorTransport
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

        t = new PolicyAwareConsistencyTransport(t, policies, tokenTracker);
        if (coalescingEnabled) t = new CoalescingTransport(t, sdkMetrics);
        if (!effectiveInterceptors.isEmpty()) t = new InterceptorTransport(t, effectiveInterceptors);
        return t;
    }

    private void buildScheduler(SdkMetrics sdkMetrics, BuildContext ctx) {
        ThreadFactory tf = useVirtualThreads
                ? Thread.ofVirtual().name("authx-sdk-", 0).factory()
                : r -> { Thread th = new Thread(r, "authx-sdk-refresh"); th.setDaemon(true); return th; };
        ctx.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        // Rotate metrics histogram every 5 seconds (single consumer, no contention)
        ctx.scheduler.scheduleAtFixedRate(sdkMetrics::rotateHistogram, 5, 5, TimeUnit.SECONDS);
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
