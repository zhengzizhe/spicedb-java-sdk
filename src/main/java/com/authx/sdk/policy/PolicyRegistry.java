package com.authx.sdk.policy;

import java.util.HashMap;
import java.util.Map;

/**
 * Hierarchical policy registry. Resolves effective policy for a given resource type.
 *
 * Resolution order (most specific wins):
 * <ol>
 *   <li>Per-resource-type policy</li>
 *   <li>Global default policy</li>
 * </ol>
 *
 * <pre>
 * PolicyRegistry.builder()
 *     .defaultPolicy(ResourcePolicy.builder()
 *         .readConsistency(ReadConsistency.session())
 *         .build())
 *     .forResourceType("document", ResourcePolicy.builder()
 *         .readConsistency(ReadConsistency.strong())
 *         .build())
 *     .forResourceType("group", ResourcePolicy.builder()
 *         .readConsistency(ReadConsistency.strong())
 *         .retry(RetryPolicy.disabled())
 *         .build())
 *     .build()
 * </pre>
 *
 * <p>Note: cache sub-policy was removed with the client-side cache
 * subsystem in ADR 2026-04-18.
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
        java.util.HashMap<java.lang.String,com.authx.sdk.policy.ResourcePolicy> resolved = new HashMap<String, ResourcePolicy>();
        for (java.util.Map.Entry<java.lang.String,com.authx.sdk.policy.ResourcePolicy> entry : this.resourceTypePolicies.entrySet()) {
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
     * Resolve read consistency for a resource type.
     */
    public ReadConsistency resolveReadConsistency(String resourceType) {
        return resolve(resourceType).readConsistency();
    }

    /** Returns the global default policy (after merging with built-in defaults). */
    public ResourcePolicy defaultPolicy() { return defaultPolicy; }

    /** Creates a registry with built-in defaults only (no per-resource-type overrides). */
    public static PolicyRegistry withDefaults() {
        return new Builder().build();
    }

    /** Creates a new {@link Builder} for constructing a custom registry. */
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
