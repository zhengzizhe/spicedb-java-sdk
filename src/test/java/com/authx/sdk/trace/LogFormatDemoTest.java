package com.authx.sdk.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.OutputStreamAppender;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

/**
 * Runnable demonstration of what log lines look like after the 2026-04-20
 * upgrade. Exercises all three enrichment layers through real Logback:
 *
 * <ol>
 *   <li>{@link LogCtx} — trace-id prefix when an OTel span is active.</li>
 *   <li>{@link Slf4jMdcBridge} — 15 {@code authx.*} MDC keys push/pop.</li>
 *   <li>{@link LogFields} — WARN+ {@code [type=... res=...]} suffix.</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :test --tests com.authx.sdk.trace.LogFormatDemoTest -i
 * </pre>
 * (The {@code -i} flag surfaces {@code System.out} from JUnit. The demo
 * writes the final formatted log lines to stdout with banners so the
 * output is obvious in test output.)
 *
 * <p>Does NOT assert anything — this is a deliberate eyeball test for
 * humans reading the demo output to verify the format.
 */
class LogFormatDemoTest {

    private static final String TRACE_ID_32 = "00112233445566778899aabbccddeeff";
    private static final String SPAN_ID_16 = "0011223344556677";

    /** Three production-shaped log patterns side-by-side + a worked example. */
    @Test
    void demo_producesFormattedLogsInStdout() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger demoLogger = ctx.getLogger("com.authx.sdk.demo");
        demoLogger.setLevel(Level.DEBUG);
        demoLogger.setAdditive(false);

        banner("Pattern 1 — minimal: %-5level %X{authx.traceId:-} - %msg%n");
        runPattern(captured, demoLogger,
                "%-5level %X{authx.traceId:-} - %msg%n");

        banner("Pattern 2 — middle: %-5level %X{authx.traceId:-} [%X{authx.action:-} %X{authx.resourceType:-}:%X{authx.resourceId:-}] %logger{20} - %msg%n");
        runPattern(captured, demoLogger,
                "%-5level %X{authx.traceId:-} [%X{authx.action:-} %X{authx.resourceType:-}:%X{authx.resourceId:-}] %logger{20} - %msg%n");

        banner("Pattern 3 — full: pattern + every authx.* key shown on matching lines");
        runPattern(captured, demoLogger,
                "%-5level %X{authx.traceId:-}/%X{authx.spanId:-} "
                        + "[%X{authx.action:-} %X{authx.resourceType:-}:%X{authx.resourceId:-} "
                        + "perm=%X{authx.permission:-} subj=%X{authx.subject:-} "
                        + "consistency=%X{authx.consistency:-}] %logger{20} - %msg%n");

        banner("Done — compare the three patterns above. All share the same underlying LogCtx/LogFields/MDC data.");
    }

    // ---- helpers ----

    private static void runPattern(ByteArrayOutputStream buf, Logger demoLogger, String pattern) {
        buf.reset();
        LoggerContext ctx = demoLogger.getLoggerContext();

        // Attach a one-shot appender to stdout with the given pattern
        PatternLayoutEncoder enc = new PatternLayoutEncoder();
        enc.setContext(ctx);
        enc.setPattern(pattern);
        enc.start();

        OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> app = new OutputStreamAppender<>();
        app.setContext(ctx);
        app.setEncoder(enc);
        app.setOutputStream(System.out);
        app.start();
        demoLogger.detachAndStopAllAppenders();
        demoLogger.addAppender(app);

        // Activate a real OTel span so LogCtx.fmt produces the trace prefix
        try (Scope ignored = makeCurrentValidSpan()) {
            // Push 15 authx.* MDC keys like InterceptorTransport does at RPC entry
            Map<String, String> fields = LogFields.toMdcMap(
                    "CHECK", "document", "doc-42", "view",
                    null, "user:alice", "minimizeLatency");
            try (Closeable mdcScope = Slf4jMdcBridge.push(fields)) {
                // INFO — body only gets trace prefix (no suffix below WARN)
                demoLogger.info(LogCtx.fmt(
                        "Incoming check for {0}:{1} by {2}",
                        "document", "doc-42", "user:alice"));

                // DEBUG — retry-style noise (now DEBUG per req-10)
                demoLogger.debug(LogCtx.fmt(
                        "Retry {0}/{1} for [{2}]: timeout after 250ms",
                        1, 3, "document"));

                // WARN — interceptor threw, gets the full suffix
                demoLogger.warn(LogCtx.fmt(
                        "Read interceptor {0} threw {1}; skipping and continuing the chain."
                                + LogFields.suffixPerm("document", "doc-42", "view", "user:alice"),
                        "com.example.AuditInterceptor", "java.lang.NullPointerException"));

                // ERROR — lifecycle failure with suffix (type-only)
                demoLogger.error(LogCtx.fmt(
                        "Startup phase {0} failed after {1}ms: {2}"
                                + LogFields.suffix("document", null, null, null),
                        "CONNECT", 150, "connection refused"));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        System.out.println(); // separator between patterns
    }

    private static void banner(String title) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  " + title);
        System.out.println("================================================================");
    }

    private static Scope makeCurrentValidSpan() {
        SpanContext sc = SpanContext.create(
                TRACE_ID_32, SPAN_ID_16,
                TraceFlags.getSampled(), TraceState.getDefault());
        return Context.current().with(Span.wrap(sc)).makeCurrent();
    }
}
