package com.authx.sdk.exception;

/** Thrown when SpiceDB returns DEADLINE_EXCEEDED. {@link #isRetryable()} returns {@code true}. */
public class AuthxTimeoutException extends AuthxException {
    public AuthxTimeoutException(String message) { super(message); }
    public AuthxTimeoutException(String message, Throwable cause) { super(message, cause); }

    @Override public boolean isRetryable() { return true; }
}
