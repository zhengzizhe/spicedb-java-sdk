package com.authx.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Build-time validation of mutually-exclusive / dependent configuration
 * combinations.
 *
 * <p>SR:C6 (target/targets mutex) is still in force.
 * SR:C7 (watchInvalidation requires cache) and related Watch-strategy
 * validation rules were removed with the cache subsystem in ADR 2026-04-18.
 */
class AuthxClientBuilderValidationTest {

    // ───────────────────────────────────────── SR:C6 — target/targets mutex

    @Test
    void build_rejects_target_and_targets_both_set() {
        com.authx.sdk.AuthxClientBuilder builder = AuthxClient.builder()
                .connection(c -> c
                        .target("dns:///a:50051")
                        .targets("dns:///b:50051")
                        .presharedKey("k"));
        java.lang.IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("mutually exclusive"),
                "message should mention mutual exclusion; was: " + ex.getMessage());
    }

    @Test
    void build_requires_at_least_target_or_targets() {
        com.authx.sdk.AuthxClientBuilder builder = AuthxClient.builder()
                .connection(c -> c.presharedKey("k"));
        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
