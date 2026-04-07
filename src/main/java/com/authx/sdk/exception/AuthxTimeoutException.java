package com.authx.sdk.exception;

public class AuthxTimeoutException extends AuthxException {
    public AuthxTimeoutException(String message) { super(message); }
    public AuthxTimeoutException(String message, Throwable cause) { super(message, cause); }

    @Override public boolean isRetryable() { return true; }
}
