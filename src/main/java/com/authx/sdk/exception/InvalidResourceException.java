package com.authx.sdk.exception;

/** Thrown when schema validation detects an unknown resource type. {@link #isRetryable()} returns {@code false}. */
public class InvalidResourceException extends AuthxException {
    public InvalidResourceException(String message) { super(message); }
    public InvalidResourceException(String message, Throwable cause) { super(message, cause); }
}
