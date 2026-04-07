package com.authx.sdk.exception;

/** Thrown when the operation is not supported (UNIMPLEMENTED). {@link #isRetryable()} returns {@code false}. */
public class AuthxUnimplementedException extends AuthxException {
    public AuthxUnimplementedException(String message) { super(message); }
    public AuthxUnimplementedException(String message, Throwable cause) { super(message, cause); }
}
