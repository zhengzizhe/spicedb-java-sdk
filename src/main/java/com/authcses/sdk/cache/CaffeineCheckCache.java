package com.authcses.sdk.cache;

import com.authcses.sdk.model.CheckResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caffeine-backed L1 cache for check results.
 * Uses a secondary index for O(k) resource invalidation instead of O(n) full scan.
 */
public class CaffeineCheckCache implements CheckCache {

    private final Cache<String, CheckResult> cache;
    // Secondary index: "resourceType:resourceId" → set of full cache keys
    private final ConcurrentHashMap<String, Set<String>> resourceIndex = new ConcurrentHashMap<>();

    public CaffeineCheckCache(Duration ttl, long maxSize) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .removalListener((key, value, cause) -> {
                    // Clean up secondary index on eviction/expiry
                    if (key instanceof String k) {
                        String resourceKey = extractResourceKey(k);
                        if (resourceKey != null) {
                            var keys = resourceIndex.get(resourceKey);
                            if (keys != null) {
                                keys.remove(k);
                                if (keys.isEmpty()) {
                                    resourceIndex.remove(resourceKey, keys);
                                }
                            }
                        }
                    }
                })
                .build();
    }

    public CaffeineCheckCache() {
        this(Duration.ofSeconds(5), 100_000);
    }

    @Override
    public Optional<CheckResult> get(String resourceType, String resourceId,
                                     String permission, String subjectType, String subjectId) {
        return Optional.ofNullable(cache.getIfPresent(key(resourceType, resourceId, permission, subjectType, subjectId)));
    }

    @Override
    public void put(String resourceType, String resourceId,
                    String permission, String subjectType, String subjectId,
                    CheckResult result) {
        String fullKey = key(resourceType, resourceId, permission, subjectType, subjectId);
        cache.put(fullKey, result);

        // Maintain secondary index
        String resourceKey = resourceType + ":" + resourceId;
        resourceIndex.computeIfAbsent(resourceKey, k -> ConcurrentHashMap.newKeySet()).add(fullKey);
    }

    @Override
    public void invalidateResource(String resourceType, String resourceId) {
        String resourceKey = resourceType + ":" + resourceId;
        Set<String> keys = resourceIndex.remove(resourceKey);
        if (keys != null) {
            cache.invalidateAll(keys); // O(k) where k = entries for this resource
        }
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
        resourceIndex.clear();
    }

    @Override
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    private static String key(String resourceType, String resourceId,
                              String permission, String subjectType, String subjectId) {
        return resourceType + ":" + resourceId + "\0" + permission + "\0" + subjectType + ":" + subjectId;
    }

    private static String extractResourceKey(String fullKey) {
        int sep = fullKey.indexOf('\0');
        return sep > 0 ? fullKey.substring(0, sep) : null;
    }
}
