package com.authx.sdk.trace;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link TraceContext} — OTel trace context utilities.
 * Since we don't have an OTel SDK configured in tests, all calls go through
 * the no-op implementation, which is still valuable to verify no-crash behavior.
 */
class TraceContextTest {

    @Test
    void traceIdReturnsNullWithNoActiveSpan() {
        // Without OTel SDK, there's no valid active span
        String traceId = TraceContext.traceId();
        assertThat(traceId).isNull();
    }

    @Test
    void traceparentReturnsNullWithNoActiveSpan() {
        String traceparent = TraceContext.traceparent();
        assertThat(traceparent).isNull();
    }

    @Test
    void startSpanReturnsUsableHandle() {
        // Even without OTel SDK, the no-op tracer returns valid handles
        try (var span = TraceContext.startSpan("test.operation", Map.of("key", "value"))) {
            span.setSuccess();
            span.setAttribute("extra", "data");
            span.setAttribute("count", 42L);
        }
        // No exception = success
    }

    @Test
    void startSpanWithNullAttributesDoesNotThrow() {
        try (var span = TraceContext.startSpan("test.operation", null)) {
            span.setSuccess();
        }
    }

    @Test
    void startSpanWithEmptyAttributesDoesNotThrow() {
        try (var span = TraceContext.startSpan("test.operation", Map.of())) {
            span.setSuccess();
        }
    }

    @Test
    void spanHandleSetErrorDoesNotThrow() {
        try (var span = TraceContext.startSpan("test.error", Map.of())) {
            span.setError(new RuntimeException("test error"));
        }
    }

    @Test
    void nestedSpansDoNotThrow() {
        try (var outer = TraceContext.startSpan("outer", Map.of())) {
            try (var inner = TraceContext.startSpan("inner", Map.of())) {
                inner.setSuccess();
            }
            outer.setSuccess();
        }
    }
}
