package com.authx.sdk.cache;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Generic cache interface. Inspired by Caffeine Cache<K,V>.
 * Replaces the old CheckCache interface that had 5 String parameters.
 *
 * @param <K> key type (e.g., CheckKey)
 * @param <V> value type (e.g., CheckResult)
 */
public interface Cache<K, V> {

    Optional<V> get(K key);

    /** High-performance lookup — returns null on miss, avoids Optional allocation. */
    V getIfPresent(K key);

    void put(K key, V value);

    /**
     * Get or compute: if key is present return its value, otherwise compute via loader,
     * store and return. Same key is loaded by at most one thread (singleFlight).
     * Default implementation is NOT singleFlight — override for atomicity.
     */
    default V getOrLoad(K key, Function<K, V> loader) {
        V v = getIfPresent(key);
        if (v != null) return v;
        v = loader.apply(key);
        if (v != null) put(key, v);
        return v;
    }

    void invalidate(K key);

    /** Invalidate all entries matching the predicate. Use sparingly — O(n) scan. */
    void invalidateAll(Predicate<K> filter);

    void invalidateAll();

    long size();

    CacheStats stats();

    static <K, V> Cache<K, V> noop() {
        @SuppressWarnings("unchecked")
        Cache<K, V> noop = (Cache<K, V>) NoopCache.INSTANCE;
        return noop;
    }
}
