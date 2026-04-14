package com.authx.sdk;

import com.authx.sdk.cache.Cache;
import com.authx.sdk.cache.CacheStats;
import com.authx.sdk.cache.IndexedCache;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;

/**
 * Manual cache control.
 *
 * <pre>
 * client.cache().invalidate("document", "doc-1");
 * client.cache().invalidateAll();
 * client.cache().size();
 * </pre>
 */
public class CacheHandle {

    private final Cache<CheckKey, CheckResult> cache;

    CacheHandle(Cache<CheckKey, CheckResult> cache) {
        this.cache = cache;
    }

    /** Invalidate all cached entries for a specific resource. */
    public void invalidate(String resourceType, String resourceId) {
        if (cache == null) return;
        String indexKey = resourceType + ":" + resourceId;
        if (cache instanceof IndexedCache<CheckKey, CheckResult> indexed) {
            indexed.invalidateByIndex(indexKey);
        } else {
            cache.invalidateAll(key -> indexKey.equals(key.resourceIndex()));
        }
    }

    /** Invalidate all cached entries. */
    public void invalidateAll() {
        if (cache != null) cache.invalidateAll();
    }

    /** Current number of cached entries. */
    public long size() {
        return cache != null ? cache.size() : 0;
    }

    /** Cache statistics snapshot (hit/miss/eviction counts). Returns empty stats if cache disabled. */
    public CacheStats stats() {
        return cache != null ? cache.stats() : CacheStats.EMPTY;
    }
}
