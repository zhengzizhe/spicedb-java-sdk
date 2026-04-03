package com.authcses.sdk.cache;

import com.authcses.sdk.model.CheckResult;

import java.util.Optional;

/**
 * Two-level cache: L1 (Caffeine local) + L2 (Redis distributed).
 *
 * Read: L1 → L2 → miss (caller goes to SpiceDB)
 * Write: L1 + L2
 * Invalidate: L1 + L2 (one instance invalidates = all instances see it via L2)
 */
public class TwoLevelCache implements CheckCache {

    private final CheckCache l1;
    private final CheckCache l2;

    public TwoLevelCache(CheckCache l1, CheckCache l2) {
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public Optional<CheckResult> get(String resourceType, String resourceId,
                                     String permission, String subjectType, String subjectId) {
        // Try L1 first
        var l1Result = l1.get(resourceType, resourceId, permission, subjectType, subjectId);
        if (l1Result.isPresent()) return l1Result;

        // Try L2
        if (l2 != null) {
            var l2Result = l2.get(resourceType, resourceId, permission, subjectType, subjectId);
            if (l2Result.isPresent()) {
                // Backfill L1
                l1.put(resourceType, resourceId, permission, subjectType, subjectId, l2Result.get());
                return l2Result;
            }
        }

        return Optional.empty();
    }

    @Override
    public void put(String resourceType, String resourceId,
                    String permission, String subjectType, String subjectId,
                    CheckResult result) {
        l1.put(resourceType, resourceId, permission, subjectType, subjectId, result);
        if (l2 != null) {
            l2.put(resourceType, resourceId, permission, subjectType, subjectId, result);
        }
    }

    @Override
    public void invalidateResource(String resourceType, String resourceId) {
        l1.invalidateResource(resourceType, resourceId);
        if (l2 != null) l2.invalidateResource(resourceType, resourceId);
    }

    @Override
    public void invalidateAll() {
        l1.invalidateAll();
        if (l2 != null) l2.invalidateAll();
    }

    @Override
    public long size() {
        return l1.size();
    }
}
