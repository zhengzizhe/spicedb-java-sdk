package com.authx.sdk.exception;

/** Thrown when SpiceDB returns RESOURCE_EXHAUSTED. {@link #isRetryable()} returns {@code false}. */
public class AuthxResourceExhaustedException extends AuthxException {
    public AuthxResourceExhaustedException(String message) { super(message); }
    public AuthxResourceExhaustedException(String message, Throwable cause) { super(message, cause); }
}
