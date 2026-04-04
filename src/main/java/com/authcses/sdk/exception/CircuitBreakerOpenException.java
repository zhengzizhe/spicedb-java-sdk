package com.authcses.sdk.exception;

public class CircuitBreakerOpenException extends AuthCsesException {
    public CircuitBreakerOpenException(String message) { super(message); }
    public CircuitBreakerOpenException(String message, Throwable cause) { super(message, cause); }
}
