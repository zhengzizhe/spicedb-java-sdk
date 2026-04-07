package com.authx.sdk.policy;

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

    private final CachePolicy cache;
    private final ReadConsistency readConsistency;
    private final RetryPolicy retry;
    private final CircuitBreakerPolicy circuitBreaker;
    private final Duration timeout;

    private ResourcePolicy(Builder builder) {
        this.cache = builder.cache;
        this.readConsistency = builder.readConsistency;
        this.retry = builder.retry;
        this.circuitBreaker = builder.circuitBreaker;
        this.timeout = builder.timeout;
    }

    public CachePolicy getCache() { return cache; }
    public ReadConsistency getReadConsistency() { return readConsistency; }
    public RetryPolicy getRetry() { return retry; }
    public CircuitBreakerPolicy getCircuitBreaker() { return circuitBreaker; }
    public Duration getTimeout() { return timeout; }

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
     */
    public static ResourcePolicy defaults() {
        return new Builder()
                .cache(CachePolicy.builder().enabled(false).build())
                .readConsistency(ReadConsistency.session())
                .retry(RetryPolicy.defaults())
                .circuitBreaker(CircuitBreakerPolicy.defaults())
                .timeout(Duration.ofSeconds(5))
                .build();
    }

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
