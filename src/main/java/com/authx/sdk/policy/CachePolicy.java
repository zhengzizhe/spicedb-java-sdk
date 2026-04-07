package com.authx.sdk.policy;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache policy for a specific scope (global, per-resource-type, or per-permission).
 *
 * <pre>
 * CachePolicy.builder()
 *     .ttl(Duration.ofSeconds(5))
 *     .maxIdleTime(Duration.ofMinutes(1))
 *     .forPermission("view", Duration.ofSeconds(10))
 *     .forPermission("delete", Duration.ofMillis(500))
 *     .build()
 * </pre>
 */
public class CachePolicy {

    private final boolean enabled;
    private final Duration ttl;
    private final @Nullable Duration maxIdleTime;
    private final Map<String, Duration> permissionTtls;

    private CachePolicy(Builder builder) {
        this.enabled = builder.enabled;
        this.ttl = builder.ttl;
        this.maxIdleTime = builder.maxIdleTime;
        this.permissionTtls = Map.copyOf(builder.permissionTtls);
    }

    /** Whether caching is enabled for this scope. */
    public boolean enabled() { return enabled; }

    /**
     * Resolve effective TTL for a specific permission.
     * Per-permission TTL overrides general TTL.
     */
    public Duration resolveTtl(String permission) {
        return permissionTtls.getOrDefault(permission, ttl);
    }

    /** Default time-to-live for cached entries. */
    public Duration ttl() { return ttl; }
    /** Maximum idle time before a cached entry is evicted, or {@code null} if not set. */
    public @Nullable Duration maxIdleTime() { return maxIdleTime; }

    /** Returns a disabled cache policy (no caching). */
    public static CachePolicy disabled() {
        return new Builder().enabled(false).build();
    }

    /** Convenience factory: enabled cache with the given TTL. */
    public static CachePolicy of(Duration ttl) {
        return new Builder().ttl(ttl).build();
    }

    /** Creates a new {@link Builder} for constructing a cache policy. */
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean enabled = true;
        private Duration ttl = Duration.ofSeconds(5);
        private Duration maxIdleTime = null;
        private final Map<String, Duration> permissionTtls = new HashMap<>();

        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder ttl(Duration ttl) { this.ttl = ttl; return this; }
        public Builder maxIdleTime(Duration maxIdleTime) { this.maxIdleTime = maxIdleTime; return this; }

        public Builder forPermission(String permission, Duration ttl) {
            permissionTtls.put(permission, ttl);
            return this;
        }

        public CachePolicy build() { return new CachePolicy(this); }
    }
}
