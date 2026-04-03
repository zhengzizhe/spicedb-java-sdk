package com.authcses.sdk;

import com.authcses.sdk.cache.CheckCache;

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

    private final CheckCache cache;

    CacheHandle(CheckCache cache) {
        this.cache = cache;
    }

    /** Invalidate all cached entries for a specific resource. */
    public void invalidate(String resourceType, String resourceId) {
        if (cache != null) cache.invalidateResource(resourceType, resourceId);
    }

    /** Invalidate all cached entries. */
    public void invalidateAll() {
        if (cache != null) cache.invalidateAll();
    }

    /** Current number of cached entries. */
    public long size() {
        return cache != null ? cache.size() : 0;
    }
}
