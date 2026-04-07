package com.authx.sdk.transport;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.spi.DistributedTokenStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public TokenTracker() {
        this(null);
    }

    public TokenTracker(DistributedTokenStore distributed) {
        this.distributed = distributed;
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
                distributedAvailable.set(true); // recovered
            } catch (Exception e) {
                if (distributedAvailable.compareAndSet(true, false)) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Distributed token store unavailable, SESSION consistency degraded to local-only: {0}",
                            e.getMessage());
                }
            }
        }
    }

    /**
     * Record a write token without resource type (backward compatibility).
     * Stores under the global key "_global".
     */
    public void recordWrite(String token) {
        recordWrite("_global", token);
    }

    public void recordRead(String token) {
        // Reserved for future use
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

    /**
     * Resolve without resource type (backward compatibility). Uses global key.
     */
    public Consistency resolve(ReadConsistency policy) {
        return resolve(policy, "_global");
    }

    private String getDistributedToken(String key) {
        if (distributed == null) return null;
        try {
            String value = distributed.get(key);
            distributedAvailable.set(true); // recovered
            return value;
        } catch (Exception e) {
            if (distributedAvailable.compareAndSet(true, false)) {
                LOG.log(System.Logger.Level.WARNING,
                        "Distributed token store unavailable, SESSION consistency degraded to local-only: {0}",
                        e.getMessage());
            }
            return null;
        }
    }

    /**
     * Get the last write token for a specific resource type.
     */
    public String getLastWriteToken(String resourceType) {
        if (resourceType == null) return getLastWriteToken();
        return lastWriteTokens.get(resourceType);
    }

    /**
     * Get any last write token (backward compatibility).
     * Returns an arbitrary token if multiple resource types have tokens, or null if none.
     */
    public String getLastWriteToken() {
        var values = lastWriteTokens.values();
        return values.isEmpty() ? null : values.iterator().next();
    }
}
