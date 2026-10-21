package com.authx.sdk.exception;

/** A malformed or unbound SpiceDB response; it must never become an allowed decision. */
public final class AuthxProtocolException extends AuthxException {
    public AuthxProtocolException(String message) { super(message); }
}
