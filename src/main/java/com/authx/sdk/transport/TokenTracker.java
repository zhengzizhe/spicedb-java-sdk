package com.authx.sdk.transport;

import com.authx.sdk.event.SdkTypedEvent;
import com.authx.sdk.event.TypedEventBus;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.spi.DistributedTokenStore;
import com.authx.sdk.trace.LogCtx;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks ZedTokens for SESSION consistency, per resource type.
 *
 * <p>Maintains the last write token per resource type so that reads after writes
 * always see at least the written state, without forcing unrelated resource types
 * to skip cache.
 *
 * <p>When a {@link DistributedTokenStore} is provided, tokens are shared across
 * SDK instances (e.g., via Redis), enabling cross-instance SESSION consistency.
 * If the distributed store becomes unavailable, silently degrades to local-only
 * tokens (logs a single warning, does not spam on every call).
 *
 * <p>Thread-safe: uses ConcurrentHashMap for per-resource-type token storage.
 */
public class TokenTracker {

    private static final System.Logger LOG = System.getLogger(TokenTracker.class.getName());

    private final ConcurrentHashMap<String, String> lastWriteTokens = new ConcurrentHashMap<>();
    private final DistributedTokenStore distributed;
    private final AtomicBoolean distributedAvailable = new AtomicBoolean(true);
    /** Cumulative count of failed token-store operations. Exposed for monitoring. */
    private final LongAdder distributedFailures = new LongAdder();
    /**
     * Optional event bus — wired by the builder to publish state-transition
     * events ({@code TokenStoreUnavailable} / {@code TokenStoreRecovered}).
     * Null = events not published (zero-cost when no bus configured).
     */
    private volatile TypedEventBus eventBus;

    public TokenTracker() {
        this(null);
    }

    public TokenTracker(DistributedTokenStore distributed) {
        this.distributed = distributed;
    }

    /** Allow the builder to wire an event bus after construction. */
    public void setEventBus(TypedEventBus bus) {
        this.eventBus = bus;
    }

    /** Cumulative failed token-store operations (read or write) since startup. */
    public long distributedFailureCount() {
        return distributedFailures.sum();
    }

    /** True if the distributed store is currently considered available. */
    public boolean isDistributedAvailable() {
        return distributedAvailable.get();
    }

    /**
     * Record a write token for a specific resource type.
     */
    public void recordWrite(String resourceType, String token) {
        if (token == null || resourceType == null) return;
        lastWriteTokens.put(resourceType, token);

        if (distributed != null) {
            try {
                distributed.set("last_write:" + resourceType, token);
                onDistributedSuccess();
            } catch (Exception e) {
                onDistributedFailure(e);
            }
        }
    }

    /**
     * Resolve a ReadConsistency policy into a concrete SpiceDB Consistency for a specific resource type.
     */
    public Consistency resolve(ReadConsistency policy, String resourceType) {
        return switch (policy) {
            case ReadConsistency.MinimizeLatency ignored ->
                    Consistency.minimizeLatency();

            case ReadConsistency.Session ignored -> {
                String token = getDistributedToken("last_write:" + resourceType);
                if (token == null) token = lastWriteTokens.get(resourceType);
                yield token != null ? Consistency.atLeast(token) : Consistency.minimizeLatency();
            }

            case ReadConsistency.Snapshot ignored ->
                    // Snapshot needs a user-provided token per-query.
                    // At policy level, fall back to STRONG.
                    Consistency.full();

            case ReadConsistency.Strong ignored ->
                    Consistency.full();

            case ReadConsistency.BoundedStaleness ignored ->
                    // BoundedStaleness: minimize latency is the closest available mapping.
                    // Full time-based bounded staleness requires SpiceDB API support (planned).
                    Consistency.minimizeLatency();
        };
    }

    private String getDistributedToken(String key) {
        if (distributed == null) return null;
        try {
            String value = distributed.get(key);
            onDistributedSuccess();
            return value;
        } catch (Exception e) {
            onDistributedFailure(e);
            return null;
        }
    }

    /**
     * Record a successful distributed-store operation. Publishes
     * {@code TokenStoreRecovered} on the unavailable→available transition so
     * subscribers can stop alerting / clear dashboards.
     */
    private void onDistributedSuccess() {
        if (distributedAvailable.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO, LogCtx.fmt(
                    "Distributed token store recovered, cross-instance SESSION consistency restored"));
            publishEvent(new SdkTypedEvent.TokenStoreRecovered(Instant.now()));
        }
    }

    /**
     * Record a failed distributed-store operation. Always increments the
     * failure counter (for monitoring); on the available→unavailable
     * transition also logs WARN and publishes {@code TokenStoreUnavailable}.
     * Subsequent failures are silent at log level (we don't want spam) but
     * the counter keeps ticking so dashboards see the sustained outage.
     */
    private void onDistributedFailure(Exception e) {
        distributedFailures.increment();
        if (distributedAvailable.compareAndSet(true, false)) {
            LOG.log(System.Logger.Level.WARNING, LogCtx.fmt(
                    "Distributed token store unavailable, SESSION consistency degraded to local-only: {0}",
                    e.getMessage()));
            publishEvent(new SdkTypedEvent.TokenStoreUnavailable(
                    Instant.now(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private void publishEvent(SdkTypedEvent event) {
        TypedEventBus bus = eventBus;
        if (bus == null) return;
        try {
            bus.publish(event);
        } catch (Exception pubEx) {
            // Don't let a bad subscriber affect token tracking.
            LOG.log(System.Logger.Level.WARNING, LogCtx.fmt(
                    "TokenTracker event publish failed: {0}", pubEx.getMessage()));
        }
    }

    /**
     * Get the last write token for a specific resource type.
     */
    public String getLastWriteToken(String resourceType) {
        if (resourceType == null) return null;
        return lastWriteTokens.get(resourceType);
    }
}
