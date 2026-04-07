package com.authx.sdk;

import com.authx.sdk.cache.Cache;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.transport.WatchCacheInvalidator;
import com.authx.sdk.watch.WatchDispatcher;

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
