package com.authx.sdk.exception;

/** Thrown when a precondition fails (FAILED_PRECONDITION). {@link #isRetryable()} returns {@code false}. */
public class AuthxPreconditionException extends AuthxException {
    public AuthxPreconditionException(String message) { super(message); }
    public AuthxPreconditionException(String message, Throwable cause) { super(message, cause); }
}
