package com.authx.sdk.lifecycle;

import com.authx.sdk.event.SdkTypedEvent;
import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.trace.LogCtx;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Manages SDK lifecycle: startup phases, state transitions, readiness probe, startup timing.
 *
 * <p>Usage in Builder.build():
 * <pre>
 * lifecycle.begin();
 * lifecycle.phase(SdkPhase.CONNECT, () -> httpTransport.connect());
 * lifecycle.phase(SdkPhase.CHANNEL, () -> new ConnectionManager(...));
 * lifecycle.phase(SdkPhase.SCHEMA, () -> schemaCache.update(...));
 * lifecycle.complete();
 * </pre>
 *
 * <p>Usage by business code:
 * <pre>
 * client.lifecycle().isReady();     // true when all phases complete
 * client.lifecycle().state();       // RUNNING
 * client.lifecycle().startupReport(); // "connect=45ms channel=12ms schema=8ms ... total=78ms"
 * </pre>
 */
public class LifecycleManager {

    private static final System.Logger LOG = System.getLogger(LifecycleManager.class.getName());

    private final AtomicReference<SdkState> state = new AtomicReference<>(SdkState.CREATED);
    private final TypedEventBus eventBus;
    private final Map<SdkPhase, Long> phaseDurations = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile long startupStartTime;
    private volatile long totalStartupMs;

    public LifecycleManager(TypedEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Mark startup beginning.
     */
    public void begin() {
        state.set(SdkState.STARTING);
        startupStartTime = System.nanoTime();
    }

    /**
     * Execute a startup phase, recording its duration.
     * If the phase throws, the error is propagated and startup fails.
     */
    public void phase(SdkPhase phase, Runnable action) {
        long start = System.nanoTime();
        try {
            action.run();
            long ms = (System.nanoTime() - start) / 1_000_000;
            phaseDurations.put(phase, ms);
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            phaseDurations.put(phase, ms);
            LOG.log(System.Logger.Level.ERROR, LogCtx.fmt(
                    "Startup phase {0} failed after {1}ms: {2}",
                    phase, ms, e.getMessage()));
            throw e;
        }
    }

    /**
     * Execute a startup phase that returns a value.
     */
    public <T> T phase(SdkPhase phase, Supplier<T> action) {
        long start = System.nanoTime();
        try {
            T result = action.get();
            long ms = (System.nanoTime() - start) / 1_000_000;
            phaseDurations.put(phase, ms);
            return result;
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            phaseDurations.put(phase, ms);
            LOG.log(System.Logger.Level.ERROR, LogCtx.fmt(
                    "Startup phase {0} failed after {1}ms: {2}",
                    phase, ms, e.getMessage()));
            throw e;
        }
    }

    /**
     * Mark startup complete. Logs startup report and fires CLIENT_READY event.
     */
    public void complete() {
        totalStartupMs = (System.nanoTime() - startupStartTime) / 1_000_000;
        state.set(SdkState.RUNNING);

        String report = startupReport();
        LOG.log(System.Logger.Level.INFO, LogCtx.fmt("SDK started in {0}ms [{1}]", totalStartupMs, report));
        eventBus.publish(new SdkTypedEvent.ClientReady(Instant.now(), Duration.ofMillis(totalStartupMs)));
    }

    /**
     * Transition to STOPPING state.
     */
    public void stopping() {
        state.set(SdkState.STOPPING);
    }

    /**
     * Transition to STOPPED state.
     */
    public void stopped() {
        state.set(SdkState.STOPPED);
    }

    /**
     * Mark as degraded (e.g., watch disconnected, refresh failing).
     */
    public void degraded(String reason) {
        if (state.get() == SdkState.RUNNING) {
            state.set(SdkState.DEGRADED);
            LOG.log(System.Logger.Level.WARNING, LogCtx.fmt("SDK degraded: {0}", reason));
        }
    }

    /**
     * Recover from degraded state.
     */
    public void recovered() {
        if (state.get() == SdkState.DEGRADED) {
            state.set(SdkState.RUNNING);
            LOG.log(System.Logger.Level.INFO, LogCtx.fmt("SDK recovered to RUNNING"));
        }
    }

    // ---- Query methods ----

    public SdkState state() {
        return state.get();
    }

    /**
     * True when all startup phases complete and client is operational.
     */
    public boolean isReady() {
        return state.get().isOperational();
    }

    /**
     * True when client is fully healthy (RUNNING, not DEGRADED).
     */
    public boolean isHealthy() {
        return state.get() == SdkState.RUNNING;
    }

    /**
     * Startup timing report: "connect=45ms channel=12ms schema=8ms total=78ms"
     */
    public String startupReport() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<SdkPhase, Long> entry : phaseDurations.entrySet()) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(entry.getKey().name().toLowerCase()).append("=").append(entry.getValue()).append("ms");
        }
        return sb.toString();
    }

    public long totalStartupMs() {
        return totalStartupMs;
    }

    public Map<SdkPhase, Long> phaseDurations() {
        return Map.copyOf(phaseDurations);
    }
}
