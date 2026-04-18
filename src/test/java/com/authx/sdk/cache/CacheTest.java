package com.authx.sdk.cache;

import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class CacheTest {

    private final CheckKey KEY1 = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("view"), SubjectRef.user("alice"));
    private final CheckKey KEY2 = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("edit"), SubjectRef.user("alice"));
    private final CheckKey KEY3 = CheckKey.of(ResourceRef.of("folder", "f1"), Permission.of("view"), SubjectRef.user("bob"));
    private final CheckResult ALLOWED = CheckResult.allowed(null);
    private final CheckResult DENIED = CheckResult.denied(null);

    CaffeineCache<CheckKey, CheckResult> createCache() {
        return new CaffeineCache<>(1000, Duration.ofSeconds(30), CheckKey::resourceIndex);
    }

    @Test void getIfPresent_miss() {
        var cache = createCache();
        assertThat(cache.getIfPresent(KEY1)).isNull();
    }

    @Test void put_then_get() {
        var cache = createCache();
        cache.put(KEY1, ALLOWED);
        assertThat(cache.getIfPresent(KEY1)).isEqualTo(ALLOWED);
        assertThat(cache.get(KEY1)).contains(ALLOWED);
    }

    @Test void invalidate_single() {
        var cache = createCache();
        cache.put(KEY1, ALLOWED);
        cache.put(KEY2, DENIED);
        cache.invalidate(KEY1);
        assertThat(cache.getIfPresent(KEY1)).isNull();
        assertThat(cache.getIfPresent(KEY2)).isEqualTo(DENIED);
    }

    @Test void invalidateByIndex_removesAllForResource() {
        var cache = createCache();
        cache.put(KEY1, ALLOWED); // document:d1
        cache.put(KEY2, DENIED);  // document:d1
        cache.put(KEY3, ALLOWED); // folder:f1
        cache.invalidateByIndex("document:d1");
        assertThat(cache.getIfPresent(KEY1)).isNull();
        assertThat(cache.getIfPresent(KEY2)).isNull();
        assertThat(cache.getIfPresent(KEY3)).isEqualTo(ALLOWED); // untouched
    }

    @Test void invalidateAll_clearsEverything() {
        var cache = createCache();
        cache.put(KEY1, ALLOWED);
        cache.put(KEY3, ALLOWED);
        cache.invalidateAll();
        assertThat(cache.size()).isZero();
    }

    @Test void stats_countsHitsAndMisses() {
        var cache = createCache();
        cache.put(KEY1, ALLOWED);
        cache.getIfPresent(KEY1); // hit
        cache.getIfPresent(KEY2); // miss
        cache.getIfPresent(KEY1); // hit
        var stats = cache.stats();
        assertThat(stats.hitCount()).isEqualTo(2);
        assertThat(stats.missCount()).isEqualTo(1);
        assertThat(stats.hitRate()).isCloseTo(0.667, within(0.01));
    }

    @Test void noopCache_alwaysMisses() {
        Cache<CheckKey, CheckResult> noop = Cache.noop();
        noop.put(KEY1, ALLOWED);
        assertThat(noop.getIfPresent(KEY1)).isNull();
        assertThat(noop.size()).isZero();
    }

    @Test void cacheStats_hitRate_emptyCache() {
        assertThat(CacheStats.EMPTY.hitRate()).isEqualTo(1.0);
    }
}
