package com.authx.sdk.policy;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Aggregated policy for a specific scope (global default, or per-resource-type override).
 * Any field left null inherits from the parent scope.
 *
 * <pre>
 * ResourcePolicy.builder()
 *     .readConsistency(ReadConsistency.session())
 *     .retry(RetryPolicy.defaults())
 *     .circuitBreaker(CircuitBreakerPolicy.defaults())
 *     .timeout(Duration.ofSeconds(5))
 *     .build()
 * </pre>
 *
 * <p>Note: the {@code cache} sub-policy was removed with the client-side
 * cache subsystem in ADR 2026-04-18.
 */
public class ResourcePolicy {

    private final @Nullable ReadConsistency readConsistency;
    private final @Nullable RetryPolicy retry;
    private final @Nullable CircuitBreakerPolicy circuitBreaker;
    private final @Nullable Duration timeout;

    private ResourcePolicy(Builder builder) {
        this.readConsistency = builder.readConsistency;
        this.retry = builder.retry;
        this.circuitBreaker = builder.circuitBreaker;
        this.timeout = builder.timeout;
    }

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
                .readConsistency(this.readConsistency != null ? this.readConsistency : parent.readConsistency)
                .retry(this.retry != null ? this.retry : parent.retry)
                .circuitBreaker(this.circuitBreaker != null ? this.circuitBreaker : parent.circuitBreaker)
                .timeout(this.timeout != null ? this.timeout : parent.timeout)
                .build();
    }

    /** Safe defaults for global fallback. */
    public static ResourcePolicy defaults() {
        return new Builder()
                .readConsistency(ReadConsistency.session())
                .retry(RetryPolicy.defaults())
                .circuitBreaker(CircuitBreakerPolicy.defaults())
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    /** Creates a new {@link Builder} for constructing a resource policy. */
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private ReadConsistency readConsistency;
        private RetryPolicy retry;
        private CircuitBreakerPolicy circuitBreaker;
        private Duration timeout;

        public Builder readConsistency(ReadConsistency rc) { this.readConsistency = rc; return this; }
        public Builder retry(RetryPolicy retry) { this.retry = retry; return this; }
        public Builder circuitBreaker(CircuitBreakerPolicy cb) { this.circuitBreaker = cb; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }

        public ResourcePolicy build() { return new ResourcePolicy(this); }
    }
}
