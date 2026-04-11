package com.authx.sdk.policy;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Aggregated policy for a specific scope (global default, or per-resource-type override).
 * Any field left null inherits from the parent scope.
 *
 * <pre>
 * ResourcePolicy.builder()
 *     .cache(CachePolicy.builder().ttl(Duration.ofSeconds(3)).build())
 *     .readConsistency(ReadConsistency.session())
 *     .retry(RetryPolicy.defaults())
 *     .circuitBreaker(CircuitBreakerPolicy.defaults())
 *     .timeout(Duration.ofSeconds(5))
 *     .build()
 * </pre>
 */
public class ResourcePolicy {

    private final @Nullable CachePolicy cache;
    private final @Nullable ReadConsistency readConsistency;
    private final @Nullable RetryPolicy retry;
    private final @Nullable CircuitBreakerPolicy circuitBreaker;
    private final @Nullable Duration timeout;

    private ResourcePolicy(Builder builder) {
        this.cache = builder.cache;
        this.readConsistency = builder.readConsistency;
        this.retry = builder.retry;
        this.circuitBreaker = builder.circuitBreaker;
        this.timeout = builder.timeout;
    }

    /** Cache policy for this scope, or {@code null} to inherit from parent. */
    public @Nullable CachePolicy cache() { return cache; }
    /** Read consistency level for this scope, or {@code null} to inherit from parent. */
    public @Nullable ReadConsistency readConsistency() { return readConsistency; }
    /** Retry policy for this scope, or {@code null} to inherit from parent. */
    public @Nullable RetryPolicy retry() { return retry; }
    /** Circuit breaker policy for this scope, or {@code null} to inherit from parent. */
    public @Nullable CircuitBreakerPolicy circuitBreaker() { return circuitBreaker; }
    /** Timeout for gRPC calls in this scope, or {@code null} to inherit from parent. */
    public @Nullable Duration timeout() { return timeout; }

    /**
     * Merge this policy with a parent (fallback). Non-null fields in this policy win.
     */
    public ResourcePolicy mergeWith(ResourcePolicy parent) {
        return new ResourcePolicy.Builder()
                .cache(this.cache != null ? this.cache : parent.cache)
                .readConsistency(this.readConsistency != null ? this.readConsistency : parent.readConsistency)
                .retry(this.retry != null ? this.retry : parent.retry)
                .circuitBreaker(this.circuitBreaker != null ? this.circuitBreaker : parent.circuitBreaker)
                .timeout(this.timeout != null ? this.timeout : parent.timeout)
                .build();
    }

    /**
     * Safe defaults for global fallback.
     *
     * <p>The cache sub-policy intentionally uses the {@link CachePolicy} builder's
     * own default (enabled, 5 s TTL) rather than forcing {@code enabled(false)}.
     * Rationale (F11-3 review fix): the SDK exposes {@code cache.enabled(true)}
     * on {@link com.authx.sdk.AuthxClientBuilder} as the operator's opt-in for
     * turning on the Caffeine L1 cache, and {@code AuthxClientBuilder} builds
     * the cache based solely on that flag. If we then hand the builder a
     * default {@link PolicyRegistry} whose {@link CachePolicy} says "cache is
     * disabled", {@link PolicyRegistry#resolveCacheTtl} returns
     * {@link Duration#ZERO}, which makes the Caffeine {@code Expiry} function
     * return 0 ns — every inserted entry expires instantly and the cache
     * reports 0 % hit rate despite being "enabled".
     *
     * <p>Users who genuinely want per-resource cache bypass can still call
     * {@code .forResource("foo", ResourcePolicy.builder().cache(CachePolicy.disabled()).build())}
     * explicitly. The global default should not silently defeat the opt-in.
     */
    public static ResourcePolicy defaults() {
        return new Builder()
                .cache(CachePolicy.builder().build())
                .readConsistency(ReadConsistency.session())
                .retry(RetryPolicy.defaults())
                .circuitBreaker(CircuitBreakerPolicy.defaults())
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    /** Creates a new {@link Builder} for constructing a resource policy. */
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private CachePolicy cache;
        private ReadConsistency readConsistency;
        private RetryPolicy retry;
        private CircuitBreakerPolicy circuitBreaker;
        private Duration timeout;

        public Builder cache(CachePolicy cache) { this.cache = cache; return this; }
        public Builder readConsistency(ReadConsistency rc) { this.readConsistency = rc; return this; }
        public Builder retry(RetryPolicy retry) { this.retry = retry; return this; }
        public Builder circuitBreaker(CircuitBreakerPolicy cb) { this.circuitBreaker = cb; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }

        public ResourcePolicy build() { return new ResourcePolicy(this); }
    }
}
