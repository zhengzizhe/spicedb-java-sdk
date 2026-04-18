package com.authx.sdk;

import com.authx.sdk.watch.WatchStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Build-time validation of mutually-exclusive / dependent configuration
 * combinations.
 *
 * <p>These tests gate SR:C6 (target/targets mutex) and SR:C7
 * (watchInvalidation requires cache) from the 2026-04-16 critical-fixes
 * review round.
 */
class AuthxClientBuilderValidationTest {

    // ───────────────────────────────────────── SR:C6 — target/targets mutex

    @Test
    void build_rejects_target_and_targets_both_set() {
        var builder = AuthxClient.builder()
                .connection(c -> c
                        .target("dns:///a:50051")
                        .targets("dns:///b:50051")
                        .presharedKey("k"));
        var ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("mutually exclusive"),
                "message should mention mutual exclusion; was: " + ex.getMessage());
    }

    @Test
    void build_requires_at_least_target_or_targets() {
        var builder = AuthxClient.builder()
                .connection(c -> c.presharedKey("k"));
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    // ───────────────────────────────────── SR:C7 — watchInvalidation needs cache

    @Test
    void build_rejects_watchInvalidation_without_cache_enabled() {
        var builder = AuthxClient.builder()
                .connection(c -> c.target("dns:///a:50051").presharedKey("k"))
                .cache(c -> c.watchInvalidation(true));  // cache.enabled left false
        var ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("watchInvalidation"),
                "message should mention watchInvalidation; was: " + ex.getMessage());
    }

    @Test
    void build_rejects_watchStrategy_without_cache_enabled() {
        // A minimal no-op WatchStrategy — we only need an instance that satisfies
        // the interface; its methods will never be invoked because build() fails.
        var builder = AuthxClient.builder()
                .connection(c -> c.target("dns:///a:50051").presharedKey("k"))
                .extend(e -> e.addWatchStrategy(new WatchStrategy() {
                    @Override public String resourceType() { return "document"; }
                }));
        var ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("watchStrategy"),
                "message should mention watchStrategy; was: " + ex.getMessage());
    }
}
