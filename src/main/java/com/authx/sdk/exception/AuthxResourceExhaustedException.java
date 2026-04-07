package com.authx.sdk.exception;

/**
 * Thrown when SpiceDB returns RESOURCE_EXHAUSTED (rate limit, quota exceeded, etc.).
 * This exception is non-retryable by default.
 */
public class AuthxResourceExhaustedException extends AuthxException {
    public AuthxResourceExhaustedException(String message) { super(message); }
    public AuthxResourceExhaustedException(String message, Throwable cause) { super(message, cause); }
}
