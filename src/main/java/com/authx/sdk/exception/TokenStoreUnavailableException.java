package com.authx.sdk.exception;

/** Required shared token coordination failed; callers must not downgrade the authorization read. */
public final class TokenStoreUnavailableException extends AuthxException {
    public TokenStoreUnavailableException(String message) { super(message); }
    public TokenStoreUnavailableException(String message, Throwable cause) { super(message, cause); }
}
