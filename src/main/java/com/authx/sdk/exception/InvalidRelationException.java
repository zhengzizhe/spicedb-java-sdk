package com.authx.sdk.exception;

public class InvalidRelationException extends AuthxException {
    public InvalidRelationException(String message) { super(message); }
    public InvalidRelationException(String message, Throwable cause) { super(message, cause); }
}
