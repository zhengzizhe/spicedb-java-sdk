package com.authcses.sdk.cache;

import java.util.Optional;
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
