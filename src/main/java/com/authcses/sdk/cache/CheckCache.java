package com.authcses.sdk.cache;

import com.authcses.sdk.model.CheckResult;

import java.util.Optional;

/**
 * L1 cache for check results. Implementations: CaffeineCheckCache (production), NoopCheckCache (disabled).
 */
public interface CheckCache {

    Optional<CheckResult> get(String resourceType, String resourceId,
                              String permission, String subjectType, String subjectId);

    void put(String resourceType, String resourceId,
             String permission, String subjectType, String subjectId,
             CheckResult result);

    /**
     * Invalidate all cached checks for a specific resource (called after grant/revoke).
     */
    void invalidateResource(String resourceType, String resourceId);

    void invalidateAll();

    long size();

    static CheckCache noop() {
        return NoopCheckCache.INSTANCE;
    }
}
