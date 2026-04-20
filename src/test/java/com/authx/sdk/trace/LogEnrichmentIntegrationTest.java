package com.authx.sdk.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.Closeable;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end composition test for the three logging/traceability layers:
 * {@link LogCtx} (trace prefix), {@link Slf4jMdcBridge} (SLF4J MDC push/pop),
 * and {@link LogFields} (WARN+ suffix).
 *
 * <p>Exercises the public contract of each class plus their composition in a
 * realistic "wrap a log message for emission" sequence. Does not depend on
 * routing {@link java.lang.System.Logger} through Logback — that wiring is
 * host-controlled; the SDK's contract is the enriched message string.
 *
 * <p>Uses {@link Span#wrap(SpanContext)} with {@link Context#makeCurrent()} so
 * that {@link Span#current()} returns a valid span without requiring an OTel
 * SDK / testing harness.
 */
class LogEnrichmentIntegrationTest {

    private static final String TRACE_ID_32 = "00112233445566778899aabbccddeeff";
    private static final String EXPECTED_SHORT = "8899aabbccddeeff"; // last 16 hex chars
    private static final String SPAN_ID_16 = "0011223344556677";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ---- LogCtx: trace prefix only when span is active + valid ----

    @Test
    void logCtx_withInvalidSpan_noPrefix() {
        // No scope made current — Span.current() is the propagated invalid span.
        String out = LogCtx.fmt("hello {0}", "world");
        assertThat(out).isEqualTo("hello world");
    }

    @Test
    void logCtx_withValidSpan_prefixesLast16HexOfTraceId() {
        try (Scope ignored = makeCurrentValidSpan()) {
            String out = LogCtx.fmt("hello {0}", "world");
            assertThat(out).isEqualTo("[trace=" + EXPECTED_SHORT + "] hello world");
        }
    }

    // ---- Slf4jMdcBridge: push/pop against real SLF4J (Logback classic in test cp) ----

    @Test
    void mdcBridge_push_setsKeys_close_clearsThem() {
        Map<String, String> fields = LogFields.toMdcMap(
                "CHECK", "document", "d-1", "view", null, "user:alice", "MinimizeLatency");

        assertThat(Slf4jMdcBridge.SLF4J_PRESENT).isTrue();

        try (Closeable scope = Slf4jMdcBridge.push(fields)) {
            assertThat(MDC.get(LogFields.KEY_ACTION)).isEqualTo("CHECK");
            assertThat(MDC.get(LogFields.KEY_RESOURCE_TYPE)).isEqualTo("document");
            assertThat(MDC.get(LogFields.KEY_RESOURCE_ID)).isEqualTo("d-1");
            assertThat(MDC.get(LogFields.KEY_PERMISSION)).isEqualTo("view");
            assertThat(MDC.get(LogFields.KEY_SUBJECT)).isEqualTo("user:alice");
            assertThat(MDC.get(LogFields.KEY_CONSISTENCY)).isEqualTo("MinimizeLatency");
            // Relation was null — must not be in MDC
            assertThat(MDC.get(LogFields.KEY_RELATION)).isNull();
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        // After close, every key pushed must be cleared
        assertThat(MDC.get(LogFields.KEY_ACTION)).isNull();
        assertThat(MDC.get(LogFields.KEY_RESOURCE_TYPE)).isNull();
        assertThat(MDC.get(LogFields.KEY_PERMISSION)).isNull();
        assertThat(MDC.get(LogFields.KEY_SUBJECT)).isNull();
    }

    @Test
    void mdcBridge_preservesHostMdc_doesNotOverwriteOnPop() {
        // Host app may have put business keys into MDC before the SDK call.
        // Our bridge only pops the keys IT pushed.
        MDC.put("business.userId", "alice-42");

        Map<String, String> fields = Map.of(LogFields.KEY_ACTION, "CHECK");
        try (Closeable scope = Slf4jMdcBridge.push(fields)) {
            assertThat(MDC.get("business.userId")).isEqualTo("alice-42");
            assertThat(MDC.get(LogFields.KEY_ACTION)).isEqualTo("CHECK");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        // SDK key gone; host key preserved
        assertThat(MDC.get(LogFields.KEY_ACTION)).isNull();
        assertThat(MDC.get("business.userId")).isEqualTo("alice-42");
    }

    // ---- LogFields: suffix formatting ----

    @Test
    void suffixPerm_includesBracketedKeyValues() {
        String s = LogFields.suffixPerm("document", "d-1", "view", "user:alice");
        assertThat(s).isEqualTo(" [type=document res=d-1 perm=view subj=user:alice]");
    }

    @Test
    void suffixRel_includesRelLabel() {
        String s = LogFields.suffixRel("document", "d-1", "editor", "user:alice");
        assertThat(s).isEqualTo(" [type=document res=d-1 rel=editor subj=user:alice]");
    }

    @Test
    void suffixPerm_allBlank_returnsEmptyString() {
        assertThat(LogFields.suffixPerm(null, null, null, null)).isEmpty();
        assertThat(LogFields.suffixPerm("", "", "", "")).isEmpty();
    }

    // ---- Composition: full WARN-enriched line ----

    @Test
    void composition_spanActive_LogCtx_plus_suffix_producesExpectedLine() {
        try (Scope ignored = makeCurrentValidSpan()) {
            String enriched = LogCtx.fmt(
                    "Interceptor {0} threw during check: {1}"
                            + LogFields.suffixPerm("document", "d-1", "view", "user:alice"),
                    "MyInterceptor", "NPE");

            // Single line, trace prefix first, suffix last, body in the middle
            assertThat(enriched).isEqualTo(
                    "[trace=" + EXPECTED_SHORT + "] "
                            + "Interceptor MyInterceptor threw during check: NPE"
                            + " [type=document res=d-1 perm=view subj=user:alice]");
        }
    }

    // ---- helpers ----

    /**
     * Activate a propagated span built from a static valid {@link SpanContext}
     * so {@link Span#current()} inside the scope returns it. No OTel SDK or
     * exporter is involved — this only exercises the OTel API surface used by
     * {@link LogCtx}.
     */
    private static Scope makeCurrentValidSpan() {
        SpanContext sc = SpanContext.create(
                TRACE_ID_32, SPAN_ID_16,
                TraceFlags.getSampled(), TraceState.getDefault());
        return Context.current().with(Span.wrap(sc)).makeCurrent();
    }
}
