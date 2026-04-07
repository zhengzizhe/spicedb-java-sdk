package com.authx.sdk.cache;

import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class TieredCacheIndexedTest {

    private static CheckKey key(String type, String id, String perm, String userId) {
        return CheckKey.of(ResourceRef.of(type, id), Permission.of(perm), SubjectRef.of("user", userId, null));
    }

    private static CheckResult allowed() {
        return new CheckResult(Permissionship.HAS_PERMISSION, "tok", Optional.empty());
    }

    @Test
    void invalidateByIndex_delegatesToBothLayers() {
        var l1 = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);
        var l2 = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);
        var tiered = new TieredCache<>(l1, l2);

        var k = key("document", "doc-1", "view", "alice");
        tiered.put(k, allowed());

        assertThat(l1.getIfPresent(k)).isNotNull();
        assertThat(l2.getIfPresent(k)).isNotNull();

        tiered.invalidateByIndex("document:doc-1");

        assertThat(l1.getIfPresent(k)).isNull();
        assertThat(l2.getIfPresent(k)).isNull();
    }

    @Test
    void invalidateByIndex_onlyAffectsMatchingResource() {
        var l1 = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);
        var l2 = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);
        var tiered = new TieredCache<>(l1, l2);

        var k1 = key("document", "doc-1", "view", "alice");
        var k2 = key("document", "doc-2", "view", "bob");
        tiered.put(k1, allowed());
        tiered.put(k2, allowed());

        tiered.invalidateByIndex("document:doc-1");

        assertThat(l1.getIfPresent(k1)).isNull();
        assertThat(l2.getIfPresent(k1)).isNull();
        assertThat(l1.getIfPresent(k2)).isNotNull();
        assertThat(l2.getIfPresent(k2)).isNotNull();
    }

    @Test
    void tieredCache_isInstanceOfIndexedCache() {
        var l1 = new CaffeineCache<CheckKey, CheckResult>(100, Duration.ofMinutes(1), CheckKey::resourceIndex);
        var l2 = new CaffeineCache<CheckKey, CheckResult>(100, Duration.ofMinutes(1), CheckKey::resourceIndex);
        var tiered = new TieredCache<>(l1, l2);

        assertThat(tiered).isInstanceOf(IndexedCache.class);
    }
}
