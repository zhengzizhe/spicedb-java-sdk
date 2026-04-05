package com.authcses.sdk;

import com.authcses.sdk.cache.Cache;
import com.authcses.sdk.cache.SchemaCache;
import com.authcses.sdk.model.CheckKey;
import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.transport.WatchCacheInvalidator;
import com.authcses.sdk.watch.WatchDispatcher;

/**
 * Aggregates caching components: check cache, schema cache, watch invalidation.
 * All fields are nullable — caching may be disabled.
 */
public record SdkCaching(
    Cache<CheckKey, CheckResult> checkCache,
    SchemaCache schemaCache,
    WatchCacheInvalidator watchInvalidator,
    WatchDispatcher watchDispatcher
) {
    // All fields nullable — caching may be disabled, watch may not be configured.

    /** Convenience: create a CacheHandle for manual cache control. */
    public CacheHandle handle() { return new CacheHandle(checkCache); }
}
