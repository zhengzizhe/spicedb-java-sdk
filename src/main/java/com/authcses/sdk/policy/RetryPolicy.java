package com.authcses.sdk.policy;

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

    public int getMaxAttempts() { return maxAttempts; }
    public Duration getBaseDelay() { return baseDelay; }
    public Duration getMaxDelay() { return maxDelay; }
    public double getMultiplier() { return multiplier; }
    public double getJitterFactor() { return jitterFactor; }

    public boolean shouldRetry(Exception e) {
        if (nonRetryableExceptions.stream().anyMatch(cls -> cls.isInstance(e))) return false;
        if (retryableExceptions.isEmpty()) return true; // default: retry all transient
        return retryableExceptions.stream().anyMatch(cls -> cls.isInstance(e));
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

    public static RetryPolicy disabled() {
        return new Builder().maxAttempts(0).build();
    }

    public static RetryPolicy defaults() {
        return new Builder()
                .doNotRetryOn(com.authcses.sdk.exception.CircuitBreakerOpenException.class)
                .doNotRetryOn(com.authcses.sdk.exception.AuthCsesAuthException.class)
                .doNotRetryOn(com.authcses.sdk.exception.AuthCsesResourceExhaustedException.class)
                .doNotRetryOn(com.authcses.sdk.exception.AuthCsesInvalidArgumentException.class)
                .doNotRetryOn(com.authcses.sdk.exception.AuthCsesUnimplementedException.class)
                .doNotRetryOn(com.authcses.sdk.exception.AuthCsesPreconditionException.class)
                .doNotRetryOn(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class)
                .build();
    }

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
