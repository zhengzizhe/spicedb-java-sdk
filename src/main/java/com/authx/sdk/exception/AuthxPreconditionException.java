package com.authx.sdk.exception;

/**
 * Thrown when SpiceDB returns FAILED_PRECONDITION (schema not written, not migrated, etc.).
 * This exception is non-retryable by default.
 */
public class AuthxPreconditionException extends AuthxException {
    public AuthxPreconditionException(String message) { super(message); }
    public AuthxPreconditionException(String message, Throwable cause) { super(message, cause); }
}
