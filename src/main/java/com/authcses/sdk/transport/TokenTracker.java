package com.authcses.sdk.transport;

import com.authcses.sdk.model.Consistency;
import com.authcses.sdk.policy.ReadConsistency;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks ZedTokens for advanced consistency levels.
 *
 * <p>Maintains:
 * <ul>
 *   <li>Last write token (for SESSION consistency)</li>
 *   <li>Last read token (for MONOTONIC_READ consistency)</li>
 *   <li>Time-indexed write token history (for BOUNDED_STALENESS)</li>
 * </ul>
 *
 * <p>Thread-safe: all fields use atomic/concurrent data structures.
 */
public class TokenTracker {

    private final AtomicReference<String> lastWriteToken = new AtomicReference<>(null);
    private final AtomicReference<String> lastReadToken = new AtomicReference<>(null);

    // Time-indexed write tokens for BOUNDED_STALENESS (newest first)
    private final ConcurrentLinkedDeque<TimestampedToken> writeHistory = new ConcurrentLinkedDeque<>();
    private final java.util.concurrent.atomic.AtomicInteger historySize = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_HISTORY = 120;

    record TimestampedToken(String token, long timestampMs) {}

    public void recordWrite(String token) {
        if (token == null) return;
        lastWriteToken.set(token);

        long now = System.currentTimeMillis();
        writeHistory.addFirst(new TimestampedToken(token, now));
        historySize.incrementAndGet();

        // Trim old entries (O(1) size check instead of O(n))
        while (historySize.get() > MAX_HISTORY) {
            if (writeHistory.pollLast() != null) historySize.decrementAndGet();
            else break;
        }
    }

    public void recordRead(String token) {
        if (token == null) return;
        lastReadToken.set(token);
    }

    /**
     * Resolve a ReadConsistency policy into a concrete Consistency for SpiceDB.
     */
    public Consistency resolve(ReadConsistency policy) {
        return switch (policy) {
            case ReadConsistency.Strong ignored ->
                    Consistency.full();

            case ReadConsistency.Session ignored -> {
                String token = lastWriteToken.get();
                yield token != null ? Consistency.atLeast(token) : Consistency.minimizeLatency();
            }

            case ReadConsistency.MinimizeLatency ignored ->
                    Consistency.minimizeLatency();

            case ReadConsistency.MonotonicRead ignored -> {
                String token = lastReadToken.get();
                yield token != null ? Consistency.atLeast(token) : Consistency.minimizeLatency();
            }

            case ReadConsistency.BoundedStaleness bs -> {
                String token = getTokenAtAge(bs.maxAge());
                yield token != null ? Consistency.atLeast(token) : Consistency.minimizeLatency();
            }

            case ReadConsistency.Snapshot ignored ->
                    // Snapshot requires the user to provide a token explicitly per-query
                    // At policy level, we fall back to full consistency
                    Consistency.full();
        };
    }

    /**
     * Get the oldest write token that is at most maxAge old.
     * Returns null if no write has happened within the window.
     */
    String getTokenAtAge(Duration maxAge) {
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        String bestToken = null;

        // Walk from newest to oldest, find the oldest token that's still within the window
        for (var entry : writeHistory) {
            if (entry.timestampMs >= cutoff) {
                bestToken = entry.token;
            } else {
                break; // older than cutoff, stop
            }
        }
        return bestToken;
    }

    public String getLastWriteToken() {
        return lastWriteToken.get();
    }

    public String getLastReadToken() {
        return lastReadToken.get();
    }
}
