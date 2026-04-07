package com.authx.sdk.trace;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.Map;

/**
 * OpenTelemetry tracing integration. Zero reflection.
 *
 * <p>Uses {@code opentelemetry-api} directly:
 * <ul>
 *   <li>Business app has OTel SDK configured → real spans exported to Jaeger/Tempo</li>
 *   <li>Business app has NO OTel SDK → all calls return no-op (zero overhead, built into OTel API)</li>
 * </ul>
 *
 * <p>Trace chain:
 * <pre>
 * Business HTTP request (@NewSpan / OTel agent)
 *   └── authx.check (SDK span — this class creates it)
 *        └── SpiceDB CheckPermission (traceparent propagated via gRPC)
 * </pre>
 */
public final class TraceContext {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("authx-sdk", "1.0.0");

    private TraceContext() {}

    /**
     * Create a child span from the current active span.
     *
     * <pre>
     * try (var span = TraceContext.startSpan("authx.check", attrs)) {
     *     result = doCheck();
     *     span.setSuccess();
     * }
     * </pre>
     */
    public static SpanHandle startSpan(String operationName, Map<String, String> attributes) {
        var builder = TRACER.spanBuilder(operationName);
        if (attributes != null) {
            attributes.forEach(builder::setAttribute);
        }
        var span = builder.startSpan();
        var scope = span.makeCurrent();
        return new SpanHandle(span, scope);
    }

    /**
     * Get the current traceId from OTel context.
     * Returns null if no active span.
     */
    public static String traceId() {
        var ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : null;
    }

    /**
     * Format W3C traceparent from current OTel context.
     * Returns null if no active span.
     */
    public static String traceparent() {
        var ctx = Span.current().getSpanContext();
        if (!ctx.isValid()) return null;
        return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" +
                (ctx.isSampled() ? "01" : "00");
    }

    /**
     * Span handle — wraps OTel Span + Scope for try-with-resources.
     */
    public static class SpanHandle implements AutoCloseable {

        private final Span span;
        private final Scope scope;

        SpanHandle(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public void setSuccess() {
            span.setStatus(StatusCode.OK);
        }

        public void setError(Throwable t) {
            span.setStatus(StatusCode.ERROR);
            span.recordException(t);
        }

        public void setAttribute(String key, String value) {
            span.setAttribute(key, value);
        }

        public void setAttribute(String key, long value) {
            span.setAttribute(key, value);
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
