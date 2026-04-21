package com.authx.sdk;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the 2026-04-18 L1 <b>decision</b> cache removal
 * (ADR 2026-04-18): the cache-related builder / client API surface that
 * carried per-request decisions and Watch subscriptions must not creep
 * back in.
 *
 * <p>This guard intentionally does <i>not</i> ban {@code schema()} — that
 * accessor was added in the 2026-04-21 schema-aware codegen work and
 * exposes read-only schema metadata (relations / permissions / subject
 * types), not cached decisions. See {@link SchemaClient}.
 */
class BuilderCacheMethodRemovedTest {

    @Test
    void builderHasNoCacheMethod() {
        boolean hasCache = Arrays.stream(AuthxClientBuilder.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("cache"));
        assertThat(hasCache)
                .as("AuthxClientBuilder.cache(...) must not exist — see ADR 2026-04-18")
                .isFalse();
    }

    @Test
    void builderHasNoCacheFields() {
        String[] forbidden = {"cacheEnabled", "cacheMaxSize", "watchInvalidation",
                              "listenerQueueOnFull", "watchStrategies"};
        for (String name : forbidden) {
            boolean present = Arrays.stream(AuthxClientBuilder.class.getDeclaredFields())
                    .anyMatch(f -> f.getName().equals(name));
            assertThat(present)
                    .as("AuthxClientBuilder must not declare field %s", name)
                    .isFalse();
        }
    }

    @Test
    void authxClientHasNoCacheMethod() {
        boolean hasCache = Arrays.stream(AuthxClient.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("cache"));
        assertThat(hasCache)
                .as("AuthxClient.cache() must not exist — see ADR 2026-04-18")
                .isFalse();
    }

    @Test
    void authxClientHasNoWatchSubscriptionMethods() {
        boolean hasWatchSub = Arrays.stream(AuthxClient.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("onRelationshipChange")
                        || m.getName().equals("offRelationshipChange"));
        assertThat(hasWatchSub)
                .as("AuthxClient Watch subscription methods must not exist")
                .isFalse();
    }
}
