package com.authx.sdk.transport;

import com.authx.sdk.cache.*;
import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the Watch → TieredCache → L1 + L2 invalidation path
 * uses IndexedCache.invalidateByIndex() (O(k)/O(1)) instead of
 * invalidateAll(Predicate) (O(n)).
 */
class WatchInvalidationPathTest {

    private static CheckKey key(String type, String id, String perm, String userId) {
        return CheckKey.of(ResourceRef.of(type, id), Permission.of(perm), SubjectRef.of("user", userId, null));
    }

    private static CheckResult allowed() {
        return new CheckResult(Permissionship.HAS_PERMISSION, "tok", Optional.empty());
    }

    @Test
    void tieredCache_invalidateByIndex_delegatesToL2IndexedCache() {
        // L1: real CaffeineCache (IndexedCache)
        var l1 = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);
        // L2: mock IndexedCache to verify invalidateByIndex is called
        @SuppressWarnings("unchecked")
        IndexedCache<CheckKey, CheckResult> l2Mock = mock(IndexedCache.class);

        var tiered = new TieredCache<>(l1, l2Mock);

        // Put entry in L1
        var k = key("document", "doc-1", "view", "alice");
        l1.put(k, allowed());

        // Simulate what WatchCacheInvalidator does
        String indexKey = "document:doc-1";
        tiered.invalidateByIndex(indexKey);

        // L1 entry should be gone
        assertThat(l1.getIfPresent(k)).isNull();
        // L2 mock should have received invalidateByIndex (NOT invalidateAll)
        verify(l2Mock).invalidateByIndex(indexKey);
        verify(l2Mock, never()).invalidateAll(any());
    }

    @Test
    void multipleInvalidators_sharedL2_idempotent() {
        var l1a = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);
        var l1b = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);
        @SuppressWarnings("unchecked")
        IndexedCache<CheckKey, CheckResult> sharedL2 = mock(IndexedCache.class);

        var tieredA = new TieredCache<>(l1a, sharedL2);
        var tieredB = new TieredCache<>(l1b, sharedL2);

        var k = key("document", "doc-1", "view", "alice");
        l1a.put(k, allowed());
        l1b.put(k, allowed());

        // Instance A's Watch fires
        tieredA.invalidateByIndex("document:doc-1");
        // Instance B's Watch fires (same resource, idempotent)
        tieredB.invalidateByIndex("document:doc-1");

        assertThat(l1a.getIfPresent(k)).isNull();
        assertThat(l1b.getIfPresent(k)).isNull();
        // L2 called twice — both should succeed (idempotent)
        verify(sharedL2, times(2)).invalidateByIndex("document:doc-1");
    }

    @Test
    void watchCacheInvalidator_codePathUsesIndexedCache() {
        // This test verifies the instanceof check in WatchCacheInvalidator
        // by confirming TieredCache IS an IndexedCache
        var l1 = new CaffeineCache<CheckKey, CheckResult>(100, Duration.ofMinutes(1), CheckKey::resourceIndex);
        @SuppressWarnings("unchecked")
        IndexedCache<CheckKey, CheckResult> l2 = mock(IndexedCache.class);
        Cache<CheckKey, CheckResult> cache = new TieredCache<>(l1, l2);

        // This is the exact check WatchCacheInvalidator does (line ~169)
        assertThat(cache).isInstanceOf(IndexedCache.class);

        // Cast and call — same as WatchCacheInvalidator
        ((IndexedCache<CheckKey, CheckResult>) cache).invalidateByIndex("folder:f-1");
        verify(l2).invalidateByIndex("folder:f-1");
    }
}
