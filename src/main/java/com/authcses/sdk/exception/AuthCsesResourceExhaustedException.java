package com.authcses.sdk.exception;

/**
 * Thrown when SpiceDB returns RESOURCE_EXHAUSTED (rate limit, quota exceeded, etc.).
 * This exception is non-retryable by default.
 */
public class AuthCsesResourceExhaustedException extends AuthCsesException {
    public AuthCsesResourceExhaustedException(String message) { super(message); }
    public AuthCsesResourceExhaustedException(String message, Throwable cause) { super(message, cause); }
}
