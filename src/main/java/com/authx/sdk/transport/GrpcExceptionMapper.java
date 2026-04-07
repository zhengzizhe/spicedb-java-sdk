package com.authx.sdk.transport;

import com.authx.sdk.exception.*;
import io.grpc.StatusRuntimeException;

/**
 * Maps gRPC {@link StatusRuntimeException} to the SDK exception hierarchy.
 * Extracted from GrpcTransport for independent testability.
 */
public final class GrpcExceptionMapper {

    private GrpcExceptionMapper() {}

    public static RuntimeException map(StatusRuntimeException e) {
        return switch (e.getStatus().getCode()) {
            case DEADLINE_EXCEEDED ->
                    new AuthxTimeoutException("SpiceDB request timed out", e);
            case UNAVAILABLE, CANCELLED ->
                    new AuthxConnectionException("SpiceDB unavailable", e);
            case UNAUTHENTICATED, PERMISSION_DENIED ->
                    new AuthxAuthException("SpiceDB auth failed", e);
            case RESOURCE_EXHAUSTED ->
                    new AuthxResourceExhaustedException("SpiceDB resource exhausted", e);
            case INVALID_ARGUMENT, NOT_FOUND, ALREADY_EXISTS, OUT_OF_RANGE ->
                    new AuthxInvalidArgumentException("SpiceDB invalid request: " + e.getStatus(), e);
            case UNIMPLEMENTED ->
                    new AuthxUnimplementedException("SpiceDB does not support this operation: " + e.getStatus().getDescription(), e);
            case FAILED_PRECONDITION ->
                    new AuthxPreconditionException("SpiceDB precondition failed (schema not written or not migrated?): " + e.getStatus().getDescription(), e);
            case ABORTED ->
                    new AuthxConnectionException("SpiceDB transaction aborted (retry may help)", e);
            default ->
                    new AuthxException("SpiceDB error: " + e.getStatus(), e);
        };
    }
}
