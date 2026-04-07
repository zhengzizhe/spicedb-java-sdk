package com.authx.sdk.exception;

public class InvalidResourceException extends AuthxException {
    public InvalidResourceException(String message) { super(message); }
    public InvalidResourceException(String message, Throwable cause) { super(message, cause); }
}
