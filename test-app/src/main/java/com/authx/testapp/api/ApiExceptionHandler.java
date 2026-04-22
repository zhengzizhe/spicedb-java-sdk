package com.authx.testapp.api;

import com.authx.sdk.exception.AuthxException;
import com.authx.sdk.exception.AuthxInvalidArgumentException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps SDK and app-level exceptions to uniform error bodies. SpiceDB
 * argument errors (invalid subject types, unknown relations) surface
 * as {@link AuthxInvalidArgumentException} which becomes a 400;
 * anything else in the {@link AuthxException} hierarchy becomes a 500
 * so the caller doesn't leak gRPC status codes.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Caller-error: unknown permission string, malformed body, etc. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    /** SpiceDB rejected the request (usually subject-type mismatch). */
    @ExceptionHandler(AuthxInvalidArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidArg(AuthxInvalidArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    /** Transport / policy / backend error — opaque to the caller. */
    @ExceptionHandler(AuthxException.class)
    public ResponseEntity<Map<String, String>> sdkError(AuthxException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage()));
    }
}
