package com.authx.sdk.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class ExceptionHierarchyTest {

    // ---- Hierarchy tests ----

    @Test void allExceptionsExtendAuthxException() {
        assertThat(AuthxAuthException.class).hasSuperclass(AuthxException.class);
        assertThat(AuthxConnectionException.class).hasSuperclass(AuthxException.class);
        assertThat(AuthxInvalidArgumentException.class).hasSuperclass(AuthxException.class);
        assertThat(AuthxPreconditionException.class).hasSuperclass(AuthxException.class);
        assertThat(AuthxResourceExhaustedException.class).hasSuperclass(AuthxException.class);
        assertThat(AuthxTimeoutException.class).hasSuperclass(AuthxException.class);
        assertThat(AuthxUnimplementedException.class).hasSuperclass(AuthxException.class);
        assertThat(CircuitBreakerOpenException.class).hasSuperclass(AuthxException.class);
        assertThat(InvalidPermissionException.class).hasSuperclass(AuthxException.class);
        assertThat(InvalidRelationException.class).hasSuperclass(AuthxException.class);
        assertThat(InvalidResourceException.class).hasSuperclass(AuthxException.class);
    }

    @Test void authxExceptionExtendsRuntimeException() {
        assertThat(AuthxException.class).hasSuperclass(RuntimeException.class);
    }

    // ---- Message and cause propagation ----

    @Test void authxException_messageOnly() {
        var ex = new AuthxException("test msg");
        assertThat(ex.getMessage()).isEqualTo("test msg");
        assertThat(ex.getCause()).isNull();
    }

    @Test void authxException_messageAndCause() {
        var cause = new RuntimeException("root");
        var ex = new AuthxException("wrapped", cause);
        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ---- isRetryable defaults ----

    @Test void defaultIsRetryable_false() {
        assertThat(new AuthxException("x").isRetryable()).isFalse();
    }

    static Stream<Arguments> retryableExceptions() {
        return Stream.of(
            Arguments.of(new AuthxConnectionException("conn"), true),
            Arguments.of(new AuthxTimeoutException("timeout"), true)
        );
    }

    @ParameterizedTest
    @MethodSource("retryableExceptions")
    void retryableExceptions_returnTrue(AuthxException ex, boolean expected) {
        assertThat(ex.isRetryable()).isEqualTo(expected);
    }

    static Stream<Arguments> nonRetryableExceptions() {
        return Stream.of(
            Arguments.of(new AuthxAuthException("auth")),
            Arguments.of(new AuthxInvalidArgumentException("invalid")),
            Arguments.of(new AuthxPreconditionException("precondition")),
            Arguments.of(new AuthxResourceExhaustedException("exhausted")),
            Arguments.of(new AuthxUnimplementedException("unimplemented")),
            Arguments.of(new CircuitBreakerOpenException("cb open")),
            Arguments.of(new InvalidPermissionException("perm")),
            Arguments.of(new InvalidRelationException("rel")),
            Arguments.of(new InvalidResourceException("res"))
        );
    }

    @ParameterizedTest
    @MethodSource("nonRetryableExceptions")
    void nonRetryableExceptions_returnFalse(AuthxException ex) {
        assertThat(ex.isRetryable()).isFalse();
    }

    // ---- Each exception can be constructed with message + cause ----

    @Test void authAuthException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new AuthxAuthException("auth fail", cause);
        assertThat(ex.getMessage()).isEqualTo("auth fail");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test void connectionException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new AuthxConnectionException("conn fail", cause);
        assertThat(ex.getMessage()).isEqualTo("conn fail");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.isRetryable()).isTrue();
    }

    @Test void timeoutException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new AuthxTimeoutException("timeout", cause);
        assertThat(ex.getMessage()).isEqualTo("timeout");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.isRetryable()).isTrue();
    }

    @Test void invalidArgumentException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new AuthxInvalidArgumentException("bad arg", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test void preconditionException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new AuthxPreconditionException("precondition", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test void resourceExhaustedException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new AuthxResourceExhaustedException("exhausted", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test void unimplementedException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new AuthxUnimplementedException("unimpl", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test void circuitBreakerOpenException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new CircuitBreakerOpenException("open", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test void invalidPermissionException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new InvalidPermissionException("perm", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test void invalidRelationException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new InvalidRelationException("rel", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test void invalidResourceException_withCause() {
        var cause = new RuntimeException("root");
        var ex = new InvalidResourceException("res", cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
