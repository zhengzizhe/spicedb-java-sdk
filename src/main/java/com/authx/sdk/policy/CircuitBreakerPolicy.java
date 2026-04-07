package com.authx.sdk.policy;

import java.time.Duration;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Circuit breaker policy with Resilience4j-level configuration.
 */
public class CircuitBreakerPolicy {

    public enum SlidingWindowType { COUNT_BASED, TIME_BASED }

    private final boolean enabled;
    private final double failureRateThreshold;
    private final double slowCallRateThreshold;
    private final Duration slowCallDuration;
    private final SlidingWindowType slidingWindowType;
    private final int slidingWindowSize;
    private final int minimumNumberOfCalls;
    private final Duration waitInOpenState;
    private final int permittedCallsInHalfOpen;
    private final Set<String> failOpenPermissions;
    private final BiConsumer<String, String> onStateChange; // (from, to)

    private CircuitBreakerPolicy(Builder builder) {
        this.enabled = builder.enabled;
        this.failureRateThreshold = builder.failureRateThreshold;
        this.slowCallRateThreshold = builder.slowCallRateThreshold;
        this.slowCallDuration = builder.slowCallDuration;
        this.slidingWindowType = builder.slidingWindowType;
        this.slidingWindowSize = builder.slidingWindowSize;
        this.minimumNumberOfCalls = builder.minimumNumberOfCalls;
        this.waitInOpenState = builder.waitInOpenState;
        this.permittedCallsInHalfOpen = builder.permittedCallsInHalfOpen;
        this.failOpenPermissions = Set.copyOf(builder.failOpenPermissions);
        this.onStateChange = builder.onStateChange;
    }

    /** Whether the circuit breaker is enabled. */
    public boolean enabled() { return enabled; }
    /** Failure rate threshold percentage (0-100). */
    public double failureRateThreshold() { return failureRateThreshold; }
    /** Slow call rate threshold percentage (0-100). */
    public double slowCallRateThreshold() { return slowCallRateThreshold; }
    /** Duration above which a call is considered slow. */
    public Duration slowCallDuration() { return slowCallDuration; }
    /** Sliding window type: count-based or time-based. */
    public SlidingWindowType slidingWindowType() { return slidingWindowType; }
    /** Size of the sliding window (number of calls or seconds). */
    public int slidingWindowSize() { return slidingWindowSize; }
    /** Minimum number of calls required before the failure rate is calculated. */
    public int minimumNumberOfCalls() { return minimumNumberOfCalls; }
    /** How long the circuit breaker stays open before transitioning to half-open. */
    public Duration waitInOpenState() { return waitInOpenState; }
    /** Number of calls permitted when the circuit breaker is half-open. */
    public int permittedCallsInHalfOpen() { return permittedCallsInHalfOpen; }
    /** Permissions that fail-open (allow) when the circuit breaker is open. */
    public Set<String> failOpenPermissions() { return failOpenPermissions; }
    /** Callback invoked on state transitions, receiving (fromState, toState). */
    public BiConsumer<String, String> onStateChange() { return onStateChange; }

    /** Returns a disabled circuit breaker policy. */
    public static CircuitBreakerPolicy disabled() {
        return new Builder().enabled(false).build();
    }

    /** Returns the default circuit breaker policy. */
    public static CircuitBreakerPolicy defaults() {
        return new Builder().build();
    }

    /** Creates a new {@link Builder} for constructing a circuit breaker policy. */
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean enabled = true;
        private double failureRateThreshold = 50.0;
        private double slowCallRateThreshold = 80.0;
        private Duration slowCallDuration = Duration.ofMillis(500);
        private SlidingWindowType slidingWindowType = SlidingWindowType.COUNT_BASED;
        private int slidingWindowSize = 100;
        private int minimumNumberOfCalls = 10;
        private Duration waitInOpenState = Duration.ofSeconds(30);
        private int permittedCallsInHalfOpen = 5;
        private Set<String> failOpenPermissions = Set.of();
        private BiConsumer<String, String> onStateChange = (from, to) -> {};

        public Builder enabled(boolean e) { this.enabled = e; return this; }
        public Builder failureRateThreshold(double t) { this.failureRateThreshold = t; return this; }
        public Builder slowCallRateThreshold(double t) { this.slowCallRateThreshold = t; return this; }
        public Builder slowCallDuration(Duration d) { this.slowCallDuration = d; return this; }
        public Builder slidingWindowType(SlidingWindowType t) { this.slidingWindowType = t; return this; }
        public Builder slidingWindowSize(int s) { this.slidingWindowSize = s; return this; }
        public Builder minimumNumberOfCalls(int n) { this.minimumNumberOfCalls = n; return this; }
        public Builder waitInOpenState(Duration d) { this.waitInOpenState = d; return this; }
        public Builder permittedCallsInHalfOpen(int n) { this.permittedCallsInHalfOpen = n; return this; }
        public Builder failOpenPermissions(Set<String> p) { this.failOpenPermissions = p; return this; }
        public Builder onStateChange(BiConsumer<String, String> cb) { this.onStateChange = cb; return this; }

        public CircuitBreakerPolicy build() { return new CircuitBreakerPolicy(this); }
    }
}
