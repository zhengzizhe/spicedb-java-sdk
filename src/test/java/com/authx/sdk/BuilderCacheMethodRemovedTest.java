package com.authx.sdk;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the 2026-04-18 L1 cache removal: asserts that the
 * cache-related builder API surface has not crept back in.
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

    @Test
    void authxClientHasNoSchemaMethod() {
        boolean hasSchema = Arrays.stream(AuthxClient.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("schema"));
        assertThat(hasSchema)
                .as("AuthxClient.schema() must not exist — SchemaCache removed")
                .isFalse();
    }
}
