package com.authx.sdk.exception;

/**
 * Thrown when SpiceDB returns UNIMPLEMENTED (unsupported operation or API version).
 * This exception is non-retryable by default.
 */
public class AuthxUnimplementedException extends AuthxException {
    public AuthxUnimplementedException(String message) { super(message); }
    public AuthxUnimplementedException(String message, Throwable cause) { super(message, cause); }
}
