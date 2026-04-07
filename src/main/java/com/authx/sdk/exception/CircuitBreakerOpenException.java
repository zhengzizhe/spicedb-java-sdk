package com.authx.sdk.exception;

/** Thrown when the circuit breaker is open for the target resource type. {@link #isRetryable()} returns {@code false}. */
public class CircuitBreakerOpenException extends AuthxException {
    public CircuitBreakerOpenException(String message) { super(message); }
    public CircuitBreakerOpenException(String message, Throwable cause) { super(message, cause); }
}
