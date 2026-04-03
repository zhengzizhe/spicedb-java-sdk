package com.authcses.sdk.circuit;

import com.authcses.sdk.exception.CircuitBreakerOpenException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Thread-safe circuit breaker using immutable state snapshots.
 * All state transitions are atomic via CAS on a single AtomicReference.
 *
 * States: CLOSED → OPEN → HALF_OPEN → CLOSED (or back to OPEN on probe failure).
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private record Snapshot(
            State state,
            int consecutiveFailures,
            int halfOpenSuccesses,
            long lastFailureTime
    ) {}

    private final int failureThreshold;
    private final long halfOpenAfterMs;
    private final int successThresholdToClose;
    private final AtomicReference<Snapshot> snapshot;

    public CircuitBreaker(int failureThreshold, Duration halfOpenAfter, int successThresholdToClose) {
        this.failureThreshold = failureThreshold;
        this.halfOpenAfterMs = halfOpenAfter.toMillis();
        this.successThresholdToClose = successThresholdToClose;
        this.snapshot = new AtomicReference<>(new Snapshot(State.CLOSED, 0, 0, 0));
    }

    public CircuitBreaker() {
        this(5, Duration.ofSeconds(10), 2);
    }

    public <T> T execute(Supplier<T> call) {
        // Pre-check: can we proceed?
        if (!tryAcquirePermission()) {
            throw new CircuitBreakerOpenException("Circuit breaker is OPEN");
        }

        try {
            T result = call.get();
            onSuccess();
            return result;
        } catch (CircuitBreakerOpenException e) {
            throw e;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    /**
     * Try to get permission to execute. Returns false if circuit is OPEN and not yet time for half-open.
     * Transitions OPEN → HALF_OPEN if timeout has elapsed (only one thread wins the CAS).
     */
    private boolean tryAcquirePermission() {
        while (true) {
            Snapshot current = snapshot.get();
            switch (current.state) {
                case CLOSED, HALF_OPEN -> {
                    return true;
                }
                case OPEN -> {
                    if (System.currentTimeMillis() - current.lastFailureTime < halfOpenAfterMs) {
                        return false; // still in open period
                    }
                    // Try to transition to HALF_OPEN — only one thread wins
                    var next = new Snapshot(State.HALF_OPEN, current.consecutiveFailures, 0, current.lastFailureTime);
                    if (snapshot.compareAndSet(current, next)) {
                        return true; // this thread is the probe
                    }
                    // CAS failed — another thread got there first, re-loop to re-check
                }
            }
        }
    }

    private void onSuccess() {
        while (true) {
            Snapshot current = snapshot.get();
            Snapshot next = switch (current.state) {
                case HALF_OPEN -> {
                    int newSuccesses = current.halfOpenSuccesses + 1;
                    if (newSuccesses >= successThresholdToClose) {
                        yield new Snapshot(State.CLOSED, 0, 0, current.lastFailureTime);
                    } else {
                        yield new Snapshot(State.HALF_OPEN, current.consecutiveFailures, newSuccesses, current.lastFailureTime);
                    }
                }
                case CLOSED -> {
                    if (current.consecutiveFailures == 0) yield current; // no change needed
                    yield new Snapshot(State.CLOSED, 0, 0, current.lastFailureTime);
                }
                case OPEN -> current; // shouldn't happen, but no-op
            };
            if (next == current || snapshot.compareAndSet(current, next)) return;
        }
    }

    private void onFailure() {
        long now = System.currentTimeMillis();
        while (true) {
            Snapshot current = snapshot.get();
            Snapshot next = switch (current.state) {
                case HALF_OPEN ->
                        new Snapshot(State.OPEN, current.consecutiveFailures, 0, now);
                case CLOSED -> {
                    int newFailures = current.consecutiveFailures + 1;
                    if (newFailures >= failureThreshold) {
                        yield new Snapshot(State.OPEN, newFailures, 0, now);
                    } else {
                        yield new Snapshot(State.CLOSED, newFailures, 0, now);
                    }
                }
                case OPEN -> current;
            };
            if (next == current || snapshot.compareAndSet(current, next)) return;
        }
    }

    public State getState() {
        return snapshot.get().state;
    }

    public int getConsecutiveFailures() {
        return snapshot.get().consecutiveFailures;
    }
}
