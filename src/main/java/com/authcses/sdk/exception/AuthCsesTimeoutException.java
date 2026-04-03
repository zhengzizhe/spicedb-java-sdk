package com.authcses.sdk.exception;

public class AuthCsesTimeoutException extends AuthCsesException {
    public AuthCsesTimeoutException(String message) { super(message); }
    public AuthCsesTimeoutException(String message, Throwable cause) { super(message, cause); }
}
