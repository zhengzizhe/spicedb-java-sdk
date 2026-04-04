package com.authcses.sdk.transport;

import com.authcses.sdk.model.Consistency;
import com.authcses.sdk.policy.ReadConsistency;
import com.authcses.sdk.spi.DistributedTokenStore;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks ZedTokens for SESSION consistency.
 *
 * <p>Maintains the last write token so that reads after writes
 * always see at least the written state.
 *
 * <p>When a {@link DistributedTokenStore} is provided, tokens are shared across
 * SDK instances (e.g., via Redis), enabling cross-instance SESSION consistency.
 *
 * <p>Thread-safe: all fields use atomic data structures.
 */
public class TokenTracker {

    private static final System.Logger LOG = System.getLogger(TokenTracker.class.getName());

    private final AtomicReference<String> lastWriteToken = new AtomicReference<>(null);
    private final DistributedTokenStore distributed;

    public TokenTracker() {
        this(null);
    }

    public TokenTracker(DistributedTokenStore distributed) {
        this.distributed = distributed;
    }

    public void recordWrite(String token) {
        if (token == null) return;
        lastWriteToken.set(token);

        if (distributed != null) {
            try {
                distributed.set("last_write", token);
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "Failed to share write token: {0}", e.getMessage());
            }
        }
    }

    public void recordRead(String token) {
        // Reserved for future use
    }

    /**
     * Resolve a ReadConsistency policy into a concrete SpiceDB Consistency.
     */
    public Consistency resolve(ReadConsistency policy) {
        return switch (policy) {
            case ReadConsistency.MinimizeLatency ignored ->
                    Consistency.minimizeLatency();

            case ReadConsistency.Session ignored -> {
                String token = getDistributedToken("last_write");
                if (token == null) token = lastWriteToken.get();
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
            return distributed.get(key);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to read distributed token: {0}", e.getMessage());
            return null;
        }
    }

    public String getLastWriteToken() {
        return lastWriteToken.get();
    }
}
