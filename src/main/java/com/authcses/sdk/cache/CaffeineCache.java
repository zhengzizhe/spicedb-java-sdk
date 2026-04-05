package com.authcses.sdk.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalListener;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Caffeine-backed generic cache with secondary index for O(k) resource invalidation.
 * Replaces CaffeineCheckCache and PolicyAwareCheckCache.
 */
public class CaffeineCache<K, V> implements IndexedCache<K, V> {

    private final com.github.benmanes.caffeine.cache.Cache<K, V> cache;
    private final ConcurrentHashMap<String, Set<K>> index;
    private final Function<K, String> indexKeyExtractor;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    /**
     * @param maxSize maximum entries
     * @param ttl default time-to-live
     * @param indexKeyExtractor extracts index key from cache key (e.g., CheckKey::resourceIndex)
     */
    public CaffeineCache(long maxSize, Duration ttl, Function<K, String> indexKeyExtractor) {
        this.indexKeyExtractor = indexKeyExtractor;
        this.index = new ConcurrentHashMap<>();
        RemovalListener<K, V> listener = (key, value, cause) -> {
            if (cause.wasEvicted()) evictions.increment();
            if (key != null) removeFromIndex(key);
        };
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .removalListener(listener)
                .build();
    }

    /**
     * Constructor with variable expiry (per-entry TTL).
     * @param expiry function that determines TTL per entry
     */
    public CaffeineCache(long maxSize, Expiry<K, V> expiry, Function<K, String> indexKeyExtractor) {
        this.indexKeyExtractor = indexKeyExtractor;
        this.index = new ConcurrentHashMap<>();
        RemovalListener<K, V> listener = (key, value, cause) -> {
            if (cause.wasEvicted()) evictions.increment();
            if (key != null) removeFromIndex(key);
        };
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(expiry)
                .removalListener(listener)
                .build();
    }

    @Override
    public Optional<V> get(K key) {
        V value = cache.getIfPresent(key);
        if (value != null) { hits.increment(); return Optional.of(value); }
        misses.increment();
        return Optional.empty();
    }

    @Override
    public V getIfPresent(K key) {
        V value = cache.getIfPresent(key);
        if (value != null) hits.increment(); else misses.increment();
        return value;
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
        String indexKey = indexKeyExtractor.apply(key);
        if (indexKey != null) {
            index.computeIfAbsent(indexKey, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    @Override
    public void invalidate(K key) {
        cache.invalidate(key);
        removeFromIndex(key);
    }

    @Override
    public void invalidateAll(Predicate<K> filter) {
        cache.asMap().keySet().removeIf(k -> {
            if (filter.test(k)) { removeFromIndex(k); return true; }
            return false;
        });
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
        index.clear();
    }

    @Override
    public void invalidateByIndex(String indexKey) {
        Set<K> keys = index.remove(indexKey);
        if (keys != null) {
            cache.invalidateAll(keys);
        }
    }

    @Override
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    @Override
    public CacheStats stats() {
        return new CacheStats(hits.sum(), misses.sum(), evictions.sum());
    }

    private void removeFromIndex(K key) {
        String indexKey = indexKeyExtractor.apply(key);
        if (indexKey != null) {
            var keys = index.get(indexKey);
            if (keys != null) {
                keys.remove(key);
                if (keys.isEmpty()) index.remove(indexKey, keys);
            }
        }
    }
}
