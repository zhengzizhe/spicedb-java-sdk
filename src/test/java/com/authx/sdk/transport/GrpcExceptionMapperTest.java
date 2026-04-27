package com.authx.sdk.transport;

import com.authx.sdk.exception.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class GrpcExceptionMapperTest {

    @Test
    void deadlineExceeded_mapsToTimeout() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));
        assertThat(result).isInstanceOf(AuthxTimeoutException.class);
        assertThat(((AuthxException) result).isRetryable()).isTrue();
    }

    @Test
    void unavailable_mapsToConnection() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.UNAVAILABLE));
        assertThat(result).isInstanceOf(AuthxConnectionException.class);
        assertThat(((AuthxException) result).isRetryable()).isTrue();
    }

    @Test
    void cancelled_mapsToConnection() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.CANCELLED));
        assertThat(result).isInstanceOf(AuthxConnectionException.class);
    }

    @Test
    void unauthenticated_mapsToAuth() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.UNAUTHENTICATED));
        assertThat(result).isInstanceOf(AuthxAuthException.class);
        assertThat(((AuthxException) result).isRetryable()).isFalse();
    }

    @Test
    void permissionDenied_mapsToAuth() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.PERMISSION_DENIED));
        assertThat(result).isInstanceOf(AuthxAuthException.class);
    }

    @Test
    void resourceExhausted_mapsToResourceExhausted() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED));
        assertThat(result).isInstanceOf(AuthxResourceExhaustedException.class);
    }

    @Test
    void invalidArgument_mapsToInvalidArgument() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.INVALID_ARGUMENT));
        assertThat(result).isInstanceOf(AuthxInvalidArgumentException.class);
    }

    @Test
    void notFound_mapsToInvalidArgument() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.NOT_FOUND));
        assertThat(result).isInstanceOf(AuthxInvalidArgumentException.class);
    }

    @Test
    void alreadyExists_mapsToInvalidArgument() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.ALREADY_EXISTS));
        assertThat(result).isInstanceOf(AuthxInvalidArgumentException.class);
    }

    @Test
    void unimplemented_mapsToUnimplemented() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.UNIMPLEMENTED));
        assertThat(result).isInstanceOf(AuthxUnimplementedException.class);
    }

    @Test
    void failedPrecondition_mapsToPrecondition() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.FAILED_PRECONDITION));
        assertThat(result).isInstanceOf(AuthxPreconditionException.class);
    }

    @Test
    void aborted_mapsToConnection() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.ABORTED));
        assertThat(result).isInstanceOf(AuthxConnectionException.class);
    }

    @Test
    void unknown_mapsToBaseException() {
        RuntimeException result = GrpcExceptionMapper.map(new StatusRuntimeException(Status.UNKNOWN));
        assertThat(result).isExactlyInstanceOf(AuthxException.class);
    }

    @Test
    void allMappedExceptions_preserveCause() {
        io.grpc.StatusRuntimeException cause = new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
        RuntimeException result = GrpcExceptionMapper.map(cause);
        assertThat(result.getCause()).isSameAs(cause);
    }
}
