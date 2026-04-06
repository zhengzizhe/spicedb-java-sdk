package com.authcses.sdk.exception;

/**
 * Thrown when SpiceDB returns UNIMPLEMENTED (unsupported operation or API version).
 * This exception is non-retryable by default.
 */
public class AuthCsesUnimplementedException extends AuthCsesException {
    public AuthCsesUnimplementedException(String message) { super(message); }
    public AuthCsesUnimplementedException(String message, Throwable cause) { super(message, cause); }
}
