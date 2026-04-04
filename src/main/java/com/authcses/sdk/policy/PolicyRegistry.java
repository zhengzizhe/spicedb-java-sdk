package com.authcses.sdk.policy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Hierarchical policy registry. Resolves effective policy for a given (resourceType, permission).
 *
 * Resolution order (most specific wins):
 * <ol>
 *   <li>Per-permission TTL override (in CachePolicy)</li>
 *   <li>Per-resource-type policy</li>
 *   <li>Global default policy</li>
 * </ol>
 *
 * <pre>
 * PolicyRegistry.builder()
 *     .defaultPolicy(ResourcePolicy.builder()
 *         .cache(CachePolicy.ofTtl(Duration.ofSeconds(5)))
 *         .readConsistency(ReadConsistency.session())
 *         .build())
 *     .forResourceType("document", ResourcePolicy.builder()
 *         .cache(CachePolicy.builder()
 *             .ttl(Duration.ofSeconds(3))
 *             .forPermission("view", Duration.ofSeconds(10))
 *             .forPermission("delete", Duration.ofMillis(500))
 *             .build())
 *         .readConsistency(ReadConsistency.strong())
 *         .build())
 *     .forResourceType("folder", ResourcePolicy.builder()
 *         .cache(CachePolicy.ofTtl(Duration.ofSeconds(30)))
 *         .build())
 *     .forResourceType("group", ResourcePolicy.builder()
 *         .cache(CachePolicy.disabled())
 *         .readConsistency(ReadConsistency.strong())
 *         .retry(RetryPolicy.disabled())
 *         .build())
 *     .build()
 * </pre>
 */
public class PolicyRegistry {

    private final ResourcePolicy defaultPolicy;
    private final Map<String, ResourcePolicy> resourceTypePolicies;
    /** Pre-computed merged policies — avoids per-call allocation on hot path. */
    private final Map<String, ResourcePolicy> resolvedPolicies;

    private PolicyRegistry(Builder builder) {
        this.defaultPolicy = builder.defaultPolicy != null
                ? builder.defaultPolicy.mergeWith(ResourcePolicy.defaults())
                : ResourcePolicy.defaults();
        this.resourceTypePolicies = Map.copyOf(builder.resourceTypePolicies);
        // Pre-compute merged policies at construction time
        var resolved = new HashMap<String, ResourcePolicy>();
        for (var entry : this.resourceTypePolicies.entrySet()) {
            resolved.put(entry.getKey(), entry.getValue().mergeWith(this.defaultPolicy));
        }
        this.resolvedPolicies = Map.copyOf(resolved);
    }

    /**
     * Resolve the effective policy for a resource type.
     * Returns pre-computed merged result — no allocation on hot path.
     */
    public ResourcePolicy resolve(String resourceType) {
        ResourcePolicy resolved = resolvedPolicies.get(resourceType);
        return resolved != null ? resolved : defaultPolicy;
    }

    /**
     * Resolve the effective cache TTL for a (resourceType, permission) pair.
     */
    public Duration resolveCacheTtl(String resourceType, String permission) {
        ResourcePolicy effective = resolve(resourceType);
        CachePolicy cache = effective.getCache();
        if (cache == null || !cache.isEnabled()) return Duration.ZERO;
        return cache.resolveTtl(permission);
    }

    /**
     * Resolve whether cache is enabled for a resource type.
     */
    public boolean isCacheEnabled(String resourceType) {
        ResourcePolicy effective = resolve(resourceType);
        return effective.getCache() != null && effective.getCache().isEnabled();
    }

    /**
     * Resolve read consistency for a resource type.
     */
    public ReadConsistency resolveReadConsistency(String resourceType) {
        return resolve(resourceType).getReadConsistency();
    }

    public ResourcePolicy getDefaultPolicy() { return defaultPolicy; }

    public static PolicyRegistry withDefaults() {
        return new Builder().build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private ResourcePolicy defaultPolicy;
        private final Map<String, ResourcePolicy> resourceTypePolicies = new HashMap<>();

        public Builder defaultPolicy(ResourcePolicy policy) {
            this.defaultPolicy = policy;
            return this;
        }

        public Builder forResourceType(String resourceType, ResourcePolicy policy) {
            resourceTypePolicies.put(resourceType, policy);
            return this;
        }

        public PolicyRegistry build() { return new PolicyRegistry(this); }
    }
}
