package com.authx.sdk.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogCtxTest {

    @Test
    void fmt_withoutSpan_returnsOriginal() {
        assertThat(LogCtx.fmt("hello")).isEqualTo("hello");
    }

    @Test
    void fmt_withoutSpan_withArgs_interpolates() {
        assertThat(LogCtx.fmt("x={0} y={1}", 1, 2)).isEqualTo("x=1 y=2");
    }

    @Test
    void fmt_nullMsg_returnsEmpty() {
        assertThat(LogCtx.fmt(null)).isEqualTo("");
        assertThat(LogCtx.fmt(null, new Object[]{"a"})).isEqualTo("");
    }

    @Test
    void fmt_invalidSpanContext_returnsOriginal() {
        // Span.getInvalid() has isValid()==false; ensure we don't emit a prefix for it.
        try (Scope s = Span.getInvalid().makeCurrent()) {
            assertThat(LogCtx.fmt("msg")).isEqualTo("msg");
            assertThat(LogCtx.fmt("x={0}", 42)).isEqualTo("x=42");
        }
    }

    @Test
    void fmt_nullArgsArray_returnsOriginalBody() {
        // When args is null, the overload with varargs receives an empty array
        // internally; we treat null-args as "no interpolation".
        assertThat(LogCtx.fmt("msg", (Object[]) null)).isEqualTo("msg");
    }

    @Test
    void fmt_malformedPattern_returnsOriginalMsg() {
        // If MessageFormat throws for a weird pattern, fmt falls back to the
        // raw msg (never throws itself).
        String weird = "unbalanced {quote";
        assertThat(LogCtx.fmt(weird, 1)).isEqualTo(weird);
    }
}
