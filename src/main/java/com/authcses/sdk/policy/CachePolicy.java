package com.authcses.sdk.policy;

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
    private final Duration maxIdleTime;
    private final Map<String, Duration> permissionTtls;

    private CachePolicy(Builder builder) {
        this.enabled = builder.enabled;
        this.ttl = builder.ttl;
        this.maxIdleTime = builder.maxIdleTime;
        this.permissionTtls = Map.copyOf(builder.permissionTtls);
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Resolve effective TTL for a specific permission.
     * Per-permission TTL overrides general TTL.
     */
    public Duration resolveTtl(String permission) {
        return permissionTtls.getOrDefault(permission, ttl);
    }

    public Duration getTtl() { return ttl; }
    public Duration getMaxIdleTime() { return maxIdleTime; }

    public static CachePolicy disabled() {
        return new Builder().enabled(false).build();
    }

    public static CachePolicy ofTtl(Duration ttl) {
        return new Builder().ttl(ttl).build();
    }

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
