package com.authcses.sdk.circuit;

import com.authcses.sdk.exception.CircuitBreakerOpenException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void startsInClosedState() {
        var cb = new CircuitBreaker(3, Duration.ofSeconds(10), 2);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void successKeepsClosed() {
        var cb = new CircuitBreaker(3, Duration.ofSeconds(10), 2);
        assertEquals("ok", cb.execute(() -> "ok"));
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void failuresBelowThresholdKeepClosed() {
        var cb = new CircuitBreaker(3, Duration.ofSeconds(10), 2);
        for (int i = 0; i < 2; i++) {
            assertThrows(RuntimeException.class, () -> cb.execute(() -> { throw new RuntimeException("fail"); }));
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(2, cb.getConsecutiveFailures());
    }

    @Test
    void failuresAtThresholdOpensCircuit() {
        var cb = new CircuitBreaker(3, Duration.ofSeconds(10), 2);
        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException.class, () -> cb.execute(() -> { throw new RuntimeException("fail"); }));
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void openCircuitThrowsWithoutCalling() {
        var cb = new CircuitBreaker(1, Duration.ofHours(1), 1);
        assertThrows(RuntimeException.class, () -> cb.execute(() -> { throw new RuntimeException("fail"); }));
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        assertThrows(CircuitBreakerOpenException.class, () -> cb.execute(() -> "should not reach"));
    }

    @Test
    void halfOpenAfterTimeout() throws InterruptedException {
        var cb = new CircuitBreaker(1, Duration.ofMillis(50), 1);
        assertThrows(RuntimeException.class, () -> cb.execute(() -> { throw new RuntimeException("fail"); }));
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        Thread.sleep(100); // wait for half-open window

        // Next call should go through (half-open)
        assertEquals("recovered", cb.execute(() -> "recovered"));
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void halfOpenFailureReOpens() throws InterruptedException {
        var cb = new CircuitBreaker(1, Duration.ofMillis(50), 1);
        assertThrows(RuntimeException.class, () -> cb.execute(() -> { throw new RuntimeException("fail"); }));

        Thread.sleep(100);

        // Half-open: one more failure → back to open
        assertThrows(RuntimeException.class, () -> cb.execute(() -> { throw new RuntimeException("fail again"); }));
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void successResetsFailureCount() {
        var cb = new CircuitBreaker(3, Duration.ofSeconds(10), 2);
        assertThrows(RuntimeException.class, () -> cb.execute(() -> { throw new RuntimeException(); }));
        assertThrows(RuntimeException.class, () -> cb.execute(() -> { throw new RuntimeException(); }));
        assertEquals(2, cb.getConsecutiveFailures());

        cb.execute(() -> "success");
        assertEquals(0, cb.getConsecutiveFailures());
    }
}
