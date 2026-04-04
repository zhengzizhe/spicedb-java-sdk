package com.authcses.sdk;

import com.authcses.sdk.cache.CheckCache;
import com.authcses.sdk.cache.SchemaCache;
import com.authcses.sdk.transport.WatchCacheInvalidator;
import com.authcses.sdk.watch.WatchDispatcher;
import java.util.Objects;

/** Aggregates caching components: check cache, schema cache, watch invalidation. */
public record SdkCaching(
    CheckCache checkCache,
    SchemaCache schemaCache,
    WatchCacheInvalidator watchInvalidator,
    WatchDispatcher watchDispatcher
) {
    public SdkCaching {
        Objects.requireNonNull(checkCache, "checkCache");
        Objects.requireNonNull(schemaCache, "schemaCache");
        Objects.requireNonNull(watchInvalidator, "watchInvalidator");
        Objects.requireNonNull(watchDispatcher, "watchDispatcher");
    }

    /** Convenience: create a CacheHandle for manual cache control. */
    public CacheHandle handle() { return new CacheHandle(checkCache); }
}
