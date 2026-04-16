package com.authx.sdk.policy;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Retry policy with exponential backoff + jitter.
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double multiplier;
    private final double jitterFactor;
    private final Set<Class<? extends Exception>> retryableExceptions;
    private final Set<Class<? extends Exception>> nonRetryableExceptions;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.baseDelay = builder.baseDelay;
        this.maxDelay = builder.maxDelay;
        this.multiplier = builder.multiplier;
        this.jitterFactor = builder.jitterFactor;
        this.retryableExceptions = Set.copyOf(builder.retryableExceptions);
        this.nonRetryableExceptions = Set.copyOf(builder.nonRetryableExceptions);
    }

    /** Maximum number of retry attempts (0 = retries disabled). */
    public int maxAttempts() { return maxAttempts; }
    /** Initial delay before the first retry. */
    public Duration baseDelay() { return baseDelay; }
    /** Upper bound on the computed retry delay. */
    public Duration maxDelay() { return maxDelay; }
    /** Exponential backoff multiplier applied to each successive attempt. */
    public double multiplier() { return multiplier; }
    /** Randomization factor (0-1) applied to delay to avoid thundering herd. */
    public double jitterFactor() { return jitterFactor; }

    /** Returns {@code true} if the given exception should be retried per the configured allow/deny lists. */
    public boolean shouldRetry(Exception e) {
        if (nonRetryableExceptions.stream().anyMatch(cls -> cls.isInstance(e))) return false;
        if (retryableExceptions.isEmpty()) return true; // default: retry all transient
        return retryableExceptions.stream().anyMatch(cls -> cls.isInstance(e));
    }

    /**
     * SR:C4 — Convenience predicate: {@code true} if the exception is on the
     * non-retryable list. Complement to {@link #shouldRetry(Exception)} but
     * more explicit at call sites that want to short-circuit the entire retry
     * pipeline (e.g. skip delay computation, log differently).
     */
    public boolean isPermanent(Throwable t) {
        if (!(t instanceof Exception e)) return false;
        return nonRetryableExceptions.stream().anyMatch(cls -> cls.isInstance(e));
    }

    /**
     * Compute delay for attempt N (0-based), with jitter.
     */
    public Duration delayForAttempt(int attempt) {
        double delay = baseDelay.toMillis() * Math.pow(multiplier, attempt);
        delay = Math.min(delay, maxDelay.toMillis());
        if (jitterFactor > 0) {
            double jitter = delay * jitterFactor * (Math.random() * 2 - 1); // ±jitterFactor
            delay = Math.max(0, delay + jitter);
        }
        return Duration.ofMillis((long) delay);
    }

    /** Returns a retry policy with retries disabled (maxAttempts = 0). */
    public static RetryPolicy disabled() {
        return new Builder().maxAttempts(0).build();
    }

    /** Returns the default retry policy (3 attempts, exponential backoff, non-retryable exceptions excluded). */
    public static RetryPolicy defaults() {
        return new Builder()
                .doNotRetryOn(com.authx.sdk.exception.CircuitBreakerOpenException.class)
                .doNotRetryOn(com.authx.sdk.exception.AuthxAuthException.class)
                .doNotRetryOn(com.authx.sdk.exception.AuthxResourceExhaustedException.class)
                .doNotRetryOn(com.authx.sdk.exception.AuthxInvalidArgumentException.class)
                .doNotRetryOn(com.authx.sdk.exception.AuthxUnimplementedException.class)
                .doNotRetryOn(com.authx.sdk.exception.AuthxPreconditionException.class)
                // SR:C4 — schema-validation errors are permanent. Previously
                // they extended AuthxException directly and were NOT matched
                // by any entry on the non-retryable list, so the retry budget
                // was consumed on every attempt even though the schema was
                // never going to change mid-call.
                .doNotRetryOn(com.authx.sdk.exception.InvalidPermissionException.class)
                .doNotRetryOn(com.authx.sdk.exception.InvalidRelationException.class)
                .doNotRetryOn(com.authx.sdk.exception.InvalidResourceException.class)
                .doNotRetryOn(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class)
                .build();
    }

    /** Creates a new {@link Builder} for constructing a retry policy. */
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int maxAttempts = 3;
        private Duration baseDelay = Duration.ofMillis(50);
        private Duration maxDelay = Duration.ofSeconds(5);
        private double multiplier = 2.0;
        private double jitterFactor = 0.2;
        private final Set<Class<? extends Exception>> retryableExceptions = new HashSet<>();
        private final Set<Class<? extends Exception>> nonRetryableExceptions = new HashSet<>();

        public Builder maxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; return this; }
        public Builder baseDelay(Duration baseDelay) { this.baseDelay = baseDelay; return this; }
        public Builder maxDelay(Duration maxDelay) { this.maxDelay = maxDelay; return this; }
        public Builder multiplier(double multiplier) { this.multiplier = multiplier; return this; }
        public Builder jitterFactor(double jitterFactor) { this.jitterFactor = jitterFactor; return this; }

        public Builder retryOn(Class<? extends Exception> exceptionClass) {
            retryableExceptions.add(exceptionClass);
            return this;
        }

        public Builder doNotRetryOn(Class<? extends Exception> exceptionClass) {
            nonRetryableExceptions.add(exceptionClass);
            return this;
        }

        public RetryPolicy build() { return new RetryPolicy(this); }
    }
}
