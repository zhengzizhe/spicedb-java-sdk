package com.authcses.sdk.exception;

/**
 * Thrown when SpiceDB returns FAILED_PRECONDITION (schema not written, not migrated, etc.).
 * This exception is non-retryable by default.
 */
public class AuthCsesPreconditionException extends AuthCsesException {
    public AuthCsesPreconditionException(String message) { super(message); }
    public AuthCsesPreconditionException(String message, Throwable cause) { super(message, cause); }
}
