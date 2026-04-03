package com.authcses.sdk.exception;

/**
 * Base exception for all SDK errors.
 */
public class AuthCsesException extends RuntimeException {

    public AuthCsesException(String message) {
        super(message);
    }

    public AuthCsesException(String message, Throwable cause) {
        super(message, cause);
    }
}
