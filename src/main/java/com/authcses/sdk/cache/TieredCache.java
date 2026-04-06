package com.authcses.sdk.cache;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Two-level cache: L1 (fast, small) + L2 (slower, larger).
 * Reads: L1 → L2 → miss. Writes: both. Invalidation: both.
 */
public class TieredCache<K, V> implements Cache<K, V> {

    private final Cache<K, V> l1;
    private final Cache<K, V> l2;

    public TieredCache(Cache<K, V> l1, Cache<K, V> l2) {
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public Optional<V> get(K key) {
        var v = l1.get(key);
        if (v.isPresent()) return v;
        v = l2.get(key);
        v.ifPresent(val -> l1.put(key, val)); // promote to L1
        return v;
    }

    @Override
    public V getIfPresent(K key) {
        V v = l1.getIfPresent(key);
        if (v != null) return v;
        v = l2.getIfPresent(key);
        if (v != null) l1.put(key, v);
        return v;
    }

    @Override public void put(K key, V value) { l1.put(key, value); l2.put(key, value); }
    @Override public void invalidate(K key) { l1.invalidate(key); l2.invalidate(key); }
    @Override public void invalidateAll(Predicate<K> filter) { l1.invalidateAll(filter); l2.invalidateAll(filter); }
    @Override public void invalidateAll() { l1.invalidateAll(); l2.invalidateAll(); }
    @Override public long size() { return l1.size() + l2.size(); }
    @Override public CacheStats stats() {
        var s1 = l1.stats();
        var s2 = l2.stats();
        // Hits: L1 hits + L2 hits (L2 is only queried on L1 miss, so L2 hits are tier-level hits)
        // Misses: L2 misses (entries that missed both levels — the true cache miss count)
        // Evictions: sum of both levels
        return new CacheStats(s1.hitCount() + s2.hitCount(), s2.missCount(), s1.evictionCount() + s2.evictionCount());
    }
}
