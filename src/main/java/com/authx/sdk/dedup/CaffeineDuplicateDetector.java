package com.authx.sdk.dedup;

import com.authx.sdk.spi.DuplicateDetector;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded-LRU, TTL-based {@link DuplicateDetector} backed by Caffeine.
 *
 * <p>This is the default implementation used by the SDK's Watch stream to
 * suppress events replayed after a reconnect. It is process-local — two JVMs
 * with their own detectors will each dedupe independently.
 *
 * <p>Memory footprint: approximately {@code maxEntries × (key size + 40 bytes)},
 * dominated by Caffeine's bookkeeping. For the default 10,000-entry configuration
 * with short string keys (~50 bytes) this is about 900 KB.
 *
 * <p>Atomicity: {@link #tryProcess(Object)} uses Caffeine's
 * {@code asMap().putIfAbsent} which is a single-lock-striped atomic operation,
 * satisfying the SPI contract even under heavy concurrent load.
 */
public final class CaffeineDuplicateDetector<K> implements DuplicateDetector<K> {

    /** Sentinel value stored against every present key — we only care about key existence. */
    private static final Object PRESENT = new Object();

    private final Cache<K, Object> seen;

    public CaffeineDuplicateDetector(int maxEntries, Duration ttl) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, got " + maxEntries);
        }
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        this.seen = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(ttl)
                .build();
    }

    @Override
    public boolean tryProcess(K key) {
        // Null keys get pass-through (fail-open): the caller wasn't able to
        // produce a stable dedup key, so we'd rather process the event than drop it.
        if (key == null) return true;
        // asMap().putIfAbsent is atomic: only ONE concurrent caller gets null back
        // (meaning "we inserted it, you're the first"), the rest get the existing value.
        return seen.asMap().putIfAbsent(key, PRESENT) == null;
    }

    /** Current number of distinct keys being tracked. Exposed for metrics/tests. */
    public long size() {
        return seen.estimatedSize();
    }

    /** Clear all tracked keys. Primarily for tests. */
    public void clear() {
        seen.invalidateAll();
    }
}
