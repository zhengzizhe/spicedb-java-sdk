package com.authx.testapp;

import com.authx.sdk.exception.AuthxAuthException;
import com.authx.sdk.exception.AuthxConnectionException;
import com.authx.sdk.exception.AuthxException;
import com.authx.sdk.exception.AuthxInvalidArgumentException;
import com.authx.sdk.exception.AuthxTimeoutException;
import com.authx.sdk.exception.CircuitBreakerOpenException;
import com.authx.sdk.exception.InvalidPermissionException;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.exception.InvalidResourceException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthxAuthException.class)
    public ResponseEntity<Map<String, String>> handleAuth(AuthxAuthException e) {
        return error(HttpStatus.UNAUTHORIZED, e);
    }

    @ExceptionHandler({AuthxConnectionException.class, CircuitBreakerOpenException.class})
    public ResponseEntity<Map<String, String>> handleUnavailable(AuthxException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e);
    }

    @ExceptionHandler(AuthxTimeoutException.class)
    public ResponseEntity<Map<String, String>> handleTimeout(AuthxTimeoutException e) {
        return error(HttpStatus.GATEWAY_TIMEOUT, e);
    }

    @ExceptionHandler({
            AuthxInvalidArgumentException.class,
            InvalidResourceException.class,
            InvalidPermissionException.class,
            InvalidRelationException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(AuthxException e) {
        return error(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(AuthxException.class)
    public ResponseEntity<Map<String, String>> handleOther(AuthxException e) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, AuthxException e) {
        return ResponseEntity.status(status).body(Map.of(
                "error", e.getClass().getSimpleName(),
                "message", String.valueOf(e.getMessage())));
    }
}
