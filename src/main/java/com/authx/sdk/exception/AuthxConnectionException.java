package com.authx.sdk.exception;

/** Thrown when SpiceDB is unavailable (UNAVAILABLE/CANCELLED/ABORTED). {@link #isRetryable()} returns {@code true}. */
public class AuthxConnectionException extends AuthxException {
    public AuthxConnectionException(String message) { super(message); }
    public AuthxConnectionException(String message, Throwable cause) { super(message, cause); }

    @Override public boolean isRetryable() { return true; }
}
