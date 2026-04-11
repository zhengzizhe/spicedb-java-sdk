package com.authx.sdk.policy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PolicyRegistryTest {

    @Test
    void defaults_whenNothingConfigured() {
        var registry = PolicyRegistry.withDefaults();
        var policy = registry.resolve("anything");

        assertNotNull(policy);
        assertEquals(ReadConsistency.session(), policy.readConsistency());
        // F11-3: default cache policy is ENABLED with the CachePolicy.Builder
        // default TTL. Previously this was force-disabled, which silently
        // nullified AuthxClientBuilder.cache.enabled(true) because the
        // policy-driven Expiry function resolved to Duration.ZERO.
        assertTrue(policy.cache().enabled(), "default cache must be enabled so builder opt-in works");
        assertEquals(Duration.ofSeconds(5), policy.cache().ttl(), "default TTL from CachePolicy.Builder is 5s");
        assertEquals(3, policy.retry().maxAttempts());
        assertTrue(policy.circuitBreaker().enabled());
        assertEquals(Duration.ofSeconds(5), policy.timeout());
    }

    @Test
    void perResourceType_overridesDefaults() {
        var registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(5)))
                        .readConsistency(ReadConsistency.session())
                        .timeout(Duration.ofSeconds(10))
                        .build())
                .forResourceType("document", ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(3)))
                        .readConsistency(ReadConsistency.strong())
                        .build())
                .build();

        // document: overrides cache + consistency, inherits timeout
        var docPolicy = registry.resolve("document");
        assertEquals(Duration.ofSeconds(3), docPolicy.cache().ttl());
        assertEquals(ReadConsistency.strong(), docPolicy.readConsistency());
        assertEquals(Duration.ofSeconds(10), docPolicy.timeout()); // inherited

        // folder: no override, uses defaults
        var folderPolicy = registry.resolve("folder");
        assertEquals(Duration.ofSeconds(5), folderPolicy.cache().ttl());
        assertEquals(ReadConsistency.session(), folderPolicy.readConsistency());
    }

    @Test
    void perPermission_cacheTtl() {
        var registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .cache(CachePolicy.builder()
                                .ttl(Duration.ofSeconds(5))
                                .build())
                        .build())
                .forResourceType("document", ResourcePolicy.builder()
                        .cache(CachePolicy.builder()
                                .ttl(Duration.ofSeconds(3))
                                .forPermission("view", Duration.ofSeconds(10))
                                .forPermission("delete", Duration.ofMillis(500))
                                .build())
                        .build())
                .build();

        // document.view → 10s (per-permission override)
        assertEquals(Duration.ofSeconds(10), registry.resolveCacheTtl("document", "view"));

        // document.delete → 500ms (per-permission override)
        assertEquals(Duration.ofMillis(500), registry.resolveCacheTtl("document", "delete"));

        // document.edit → 3s (falls back to document type TTL)
        assertEquals(Duration.ofSeconds(3), registry.resolveCacheTtl("document", "edit"));

        // folder.view → 5s (falls back to global default)
        assertEquals(Duration.ofSeconds(5), registry.resolveCacheTtl("folder", "view"));
    }

    @Test
    void cacheDisabled_perResourceType() {
        var registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(5)))
                        .build())
                .forResourceType("group", ResourcePolicy.builder()
                        .cache(CachePolicy.disabled())
                        .build())
                .build();

        assertTrue(registry.isCacheEnabled("document"));
        assertFalse(registry.isCacheEnabled("group"));
    }

    @Test
    void retryDisabled_perResourceType() {
        var registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.defaults())
                        .build())
                .forResourceType("group", ResourcePolicy.builder()
                        .retry(RetryPolicy.disabled())
                        .build())
                .build();

        assertEquals(3, registry.resolve("document").retry().maxAttempts());
        assertEquals(0, registry.resolve("group").retry().maxAttempts());
    }

    @Test
    void differentConsistency_perResourceType() {
        var registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.session())
                        .build())
                .forResourceType("document", ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.boundedStaleness(Duration.ofSeconds(3)))
                        .build())
                .forResourceType("group", ResourcePolicy.builder()
                        .readConsistency(ReadConsistency.strong())
                        .build())
                .build();

        assertEquals(ReadConsistency.session(), registry.resolveReadConsistency("folder"));
        assertInstanceOf(ReadConsistency.BoundedStaleness.class, registry.resolveReadConsistency("document"));
        assertEquals(ReadConsistency.strong(), registry.resolveReadConsistency("group"));
    }

    @Test
    void realWorldConfig() {
        // Simulates a real production configuration
        var registry = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .cache(CachePolicy.builder()
                                .ttl(Duration.ofSeconds(5))
                                .build())
                        .readConsistency(ReadConsistency.session())
                        .retry(RetryPolicy.builder().maxAttempts(3).build())
                        .circuitBreaker(CircuitBreakerPolicy.defaults())
                        .timeout(Duration.ofSeconds(5))
                        .build())
                .forResourceType("document", ResourcePolicy.builder()
                        .cache(CachePolicy.builder()
                                .ttl(Duration.ofSeconds(3))
                                .forPermission("view", Duration.ofSeconds(10))
                                .forPermission("delete", Duration.ofMillis(500))
                                .build())
                        .timeout(Duration.ofSeconds(3))
                        .build())
                .forResourceType("folder", ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(30)))
                        .readConsistency(ReadConsistency.boundedStaleness(Duration.ofSeconds(10)))
                        .build())
                .forResourceType("group", ResourcePolicy.builder()
                        .cache(CachePolicy.disabled())
                        .readConsistency(ReadConsistency.strong())
                        .retry(RetryPolicy.disabled())
                        .build())
                .build();

        // document.view: cache 10s, session consistency, 3s timeout
        assertEquals(Duration.ofSeconds(10), registry.resolveCacheTtl("document", "view"));
        assertEquals(Duration.ofSeconds(3), registry.resolve("document").timeout());

        // folder: cache 30s, bounded staleness 10s
        assertEquals(Duration.ofSeconds(30), registry.resolveCacheTtl("folder", "view"));
        assertInstanceOf(ReadConsistency.BoundedStaleness.class, registry.resolveReadConsistency("folder"));

        // group: no cache, strong consistency, no retry
        assertFalse(registry.isCacheEnabled("group"));
        assertEquals(ReadConsistency.strong(), registry.resolveReadConsistency("group"));
        assertEquals(0, registry.resolve("group").retry().maxAttempts());

        // unknown type: inherits all defaults
        assertTrue(registry.isCacheEnabled("some-new-type"));
        assertEquals(ReadConsistency.session(), registry.resolveReadConsistency("some-new-type"));
    }
}
