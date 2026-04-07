package com.authx.sdk.exception;

/**
 * Base exception for all SDK errors.
 */
public class AuthxException extends RuntimeException {

    public AuthxException(String message) {
        super(message);
    }

    public AuthxException(String message, Throwable cause) {
        super(message, cause);
    }
}
