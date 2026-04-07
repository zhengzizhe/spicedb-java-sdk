package com.authx.sdk.exception;

/**
 * Thrown when SpiceDB returns INVALID_ARGUMENT (malformed request, bad schema reference, etc.).
 * This exception is non-retryable by default.
 */
public class AuthxInvalidArgumentException extends AuthxException {
    public AuthxInvalidArgumentException(String message) { super(message); }
    public AuthxInvalidArgumentException(String message, Throwable cause) { super(message, cause); }
}
