package com.authx.sdk.exception;

/** Thrown when schema validation detects an unknown permission. {@link #isRetryable()} returns {@code false}. */
public class InvalidPermissionException extends AuthxException {
    public InvalidPermissionException(String message) { super(message); }
    public InvalidPermissionException(String message, Throwable cause) { super(message, cause); }
}
