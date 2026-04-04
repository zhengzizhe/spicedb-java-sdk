package com.authcses.sdk.cache;

/** Immutable cache statistics snapshot. */
public record CacheStats(long hitCount, long missCount, long evictionCount) {
    public double hitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 1.0 : (double) hitCount / total;
    }
    public long requestCount() { return hitCount + missCount; }

    public static final CacheStats EMPTY = new CacheStats(0, 0, 0);
}
