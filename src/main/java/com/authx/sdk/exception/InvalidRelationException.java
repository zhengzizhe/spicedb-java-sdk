package com.authx.sdk.exception;

/** Thrown when schema validation detects an unknown relation. {@link #isRetryable()} returns {@code false}. */
public class InvalidRelationException extends AuthxException {
    public InvalidRelationException(String message) { super(message); }
    public InvalidRelationException(String message, Throwable cause) { super(message, cause); }
}
