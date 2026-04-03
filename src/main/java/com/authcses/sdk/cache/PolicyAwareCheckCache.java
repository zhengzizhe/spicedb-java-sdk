package com.authcses.sdk.cache;

import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.policy.PolicyRegistry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Policy-aware cache with per-resource-type, per-permission TTL.
 * Uses Caffeine's variable expiry to give each entry a different TTL
 * based on PolicyRegistry resolution.
 *
 * <pre>
 * document.view → 10s TTL
 * document.delete → 500ms TTL
 * folder.view → 30s TTL
 * group.* → cache disabled (TTL = 0)
 * </pre>
 */
public class PolicyAwareCheckCache implements CheckCache {

    private final Cache<String, CachedEntry> cache;
    private final PolicyRegistry policyRegistry;
    private final ConcurrentHashMap<String, Set<String>> resourceIndex = new ConcurrentHashMap<>();

    record CachedEntry(CheckResult result, String resourceType, String permission) {}

    public PolicyAwareCheckCache(PolicyRegistry policyRegistry, long maxSize) {
        this.policyRegistry = policyRegistry;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfter(new Expiry<String, CachedEntry>() {
                    @Override
                    public long expireAfterCreate(String key, CachedEntry entry, long currentTime) {
                        Duration ttl = policyRegistry.resolveCacheTtl(entry.resourceType, entry.permission);
                        return ttl.toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, CachedEntry entry, long currentTime, long currentDuration) {
                        return currentDuration; // keep original TTL on update
                    }

                    @Override
                    public long expireAfterRead(String key, CachedEntry entry, long currentTime, long currentDuration) {
                        return currentDuration; // don't extend on read
                    }
                })
                .removalListener((key, value, cause) -> {
                    if (key instanceof String k) {
                        String resourceKey = extractResourceKey(k);
                        if (resourceKey != null) {
                            var keys = resourceIndex.get(resourceKey);
                            if (keys != null) {
                                keys.remove(k);
                                if (keys.isEmpty()) resourceIndex.remove(resourceKey, keys);
                            }
                        }
                    }
                })
                .build();
    }

    @Override
    public Optional<CheckResult> get(String resourceType, String resourceId,
                                     String permission, String subjectType, String subjectId) {
        // Check if cache is disabled for this resource type
        if (!policyRegistry.isCacheEnabled(resourceType)) {
            return Optional.empty();
        }

        var entry = cache.getIfPresent(key(resourceType, resourceId, permission, subjectType, subjectId));
        return entry != null ? Optional.of(entry.result) : Optional.empty();
    }

    @Override
    public void put(String resourceType, String resourceId,
                    String permission, String subjectType, String subjectId,
                    CheckResult result) {
        if (!policyRegistry.isCacheEnabled(resourceType)) {
            return;
        }

        String fullKey = key(resourceType, resourceId, permission, subjectType, subjectId);
        cache.put(fullKey, new CachedEntry(result, resourceType, permission));

        String resourceKey = resourceType + ":" + resourceId;
        resourceIndex.computeIfAbsent(resourceKey, k -> ConcurrentHashMap.newKeySet()).add(fullKey);
    }

    @Override
    public void invalidateResource(String resourceType, String resourceId) {
        String resourceKey = resourceType + ":" + resourceId;
        Set<String> keys = resourceIndex.remove(resourceKey);
        if (keys != null) {
            cache.invalidateAll(keys);
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
