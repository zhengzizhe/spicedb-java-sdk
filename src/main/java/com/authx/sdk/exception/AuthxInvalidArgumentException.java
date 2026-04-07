package com.authx.sdk.exception;

/** Thrown for invalid requests (INVALID_ARGUMENT/NOT_FOUND/ALREADY_EXISTS). {@link #isRetryable()} returns {@code false}. */
public class AuthxInvalidArgumentException extends AuthxException {
    public AuthxInvalidArgumentException(String message) { super(message); }
    public AuthxInvalidArgumentException(String message, Throwable cause) { super(message, cause); }
}
