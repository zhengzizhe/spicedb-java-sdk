package com.authcses.sdk.exception;

/**
 * Thrown when SpiceDB returns INVALID_ARGUMENT (malformed request, bad schema reference, etc.).
 * This exception is non-retryable by default.
 */
public class AuthCsesInvalidArgumentException extends AuthCsesException {
    public AuthCsesInvalidArgumentException(String message) { super(message); }
    public AuthCsesInvalidArgumentException(String message, Throwable cause) { super(message, cause); }
}
