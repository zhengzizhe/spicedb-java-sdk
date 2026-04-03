package com.authcses.sdk.exception;

public class CircuitBreakerOpenException extends AuthCsesException {
    public CircuitBreakerOpenException(String message) { super(message); }
}
