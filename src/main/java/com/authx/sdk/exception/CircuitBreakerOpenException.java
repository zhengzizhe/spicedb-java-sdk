package com.authx.sdk.exception;

public class CircuitBreakerOpenException extends AuthxException {
    public CircuitBreakerOpenException(String message) { super(message); }
    public CircuitBreakerOpenException(String message, Throwable cause) { super(message, cause); }
}
