package com.authx.sdk.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

import java.text.MessageFormat;

/**
 * Stateless log-message enricher. Prepends {@code [trace=<16hex>] } to the
 * message when a valid OTel {@link Span} is current; returns the message
 * unchanged otherwise.
 *
 * <p><b>Stability:</b> Every path is wrapped in try/catch. If OTel API throws
 * unexpectedly, returns the original message.
 *
 * @see LogFields for MDC field constants
 * @see Slf4jMdcBridge for structured-field bridging
 */
public final class LogCtx {

    private static final int DISPLAY_TRACE_ID_LEN = 16;

    private LogCtx() {}

    /** Returns {@code msg} unchanged; prefixed with trace-id when available. */
    public static String fmt(String msg) {
        if (msg == null) return "";
        String prefix = tracePrefix();
        return prefix.isEmpty() ? msg : prefix + msg;
    }

    /** MessageFormat-style interpolation ({@code {0}}, {@code {1}}, ...) + trace prefix. */
    public static String fmt(String msg, Object... args) {
        if (msg == null) return "";
        String body;
        try {
            body = args == null || args.length == 0 ? msg : MessageFormat.format(msg, args);
        } catch (RuntimeException e) {
            body = msg;
        }
        String prefix = tracePrefix();
        return prefix.isEmpty() ? body : prefix + body;
    }

    private static String tracePrefix() {
        try {
            SpanContext ctx = Span.current().getSpanContext();
            if (!ctx.isValid()) return "";
            String full = ctx.getTraceId();
            if (full == null || full.length() < DISPLAY_TRACE_ID_LEN) return "";
            String shortId = full.substring(full.length() - DISPLAY_TRACE_ID_LEN);
            return "[trace=" + shortId + "] ";
        } catch (Throwable t) {
            return "";
        }
    }
}
