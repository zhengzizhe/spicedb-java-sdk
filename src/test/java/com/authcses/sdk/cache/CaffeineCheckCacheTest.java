package com.authcses.sdk.cache;

import com.authcses.sdk.model.CheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CaffeineCheckCacheTest {

    private CaffeineCheckCache cache;

    @BeforeEach
    void setup() {
        cache = new CaffeineCheckCache(Duration.ofSeconds(10), 1000);
    }

    @Test
    void putAndGet() {
        cache.put("document", "doc-1", "view", "user", "alice", CheckResult.allowed("t1"));

        var result = cache.get("document", "doc-1", "view", "user", "alice");
        assertTrue(result.isPresent());
        assertTrue(result.get().hasPermission());
    }

    @Test
    void miss() {
        var result = cache.get("document", "doc-1", "view", "user", "nobody");
        assertTrue(result.isEmpty());
    }

    @Test
    void invalidateResource() {
        cache.put("document", "doc-1", "view", "user", "alice", CheckResult.allowed("t1"));
        cache.put("document", "doc-1", "edit", "user", "bob", CheckResult.denied("t1"));
        cache.put("document", "doc-2", "view", "user", "alice", CheckResult.allowed("t1"));

        cache.invalidateResource("document", "doc-1");

        assertTrue(cache.get("document", "doc-1", "view", "user", "alice").isEmpty());
        assertTrue(cache.get("document", "doc-1", "edit", "user", "bob").isEmpty());
        assertTrue(cache.get("document", "doc-2", "view", "user", "alice").isPresent());
    }

    @Test
    void invalidateAll() {
        cache.put("document", "doc-1", "view", "user", "alice", CheckResult.allowed("t1"));
        cache.invalidateAll();
        assertEquals(0, cache.size());
    }
}
