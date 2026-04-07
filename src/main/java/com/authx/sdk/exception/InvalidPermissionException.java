package com.authx.sdk.exception;

public class InvalidPermissionException extends AuthxException {
    public InvalidPermissionException(String message) { super(message); }
    public InvalidPermissionException(String message, Throwable cause) { super(message, cause); }
}
