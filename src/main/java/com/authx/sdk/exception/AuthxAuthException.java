package com.authx.sdk.exception;

/** Thrown when authentication fails (UNAUTHENTICATED/PERMISSION_DENIED). {@link #isRetryable()} returns {@code false}. */
public class AuthxAuthException extends AuthxException {
    public AuthxAuthException(String message) { super(message); }
    public AuthxAuthException(String message, Throwable cause) { super(message, cause); }
}
