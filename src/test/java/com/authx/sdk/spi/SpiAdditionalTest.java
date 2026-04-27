package com.authx.sdk.spi;

import com.authx.sdk.model.enums.SdkAction;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SpiAdditionalTest {

    // ---- SdkClock ----
    @Nested class SdkClockTest {
        @Test void systemClockReturnsReasonableValues() {
            long millis = SdkClock.SYSTEM.currentTimeMillis();
            assertThat(millis).isGreaterThan(0);

            long nanos = SdkClock.SYSTEM.nanoTime();
            assertThat(nanos).isGreaterThan(0);
        }

        @Test void fixedClock() {
            com.authx.sdk.spi.SdkClock.Fixed clock = new SdkClock.Fixed(1000L);
            assertThat(clock.currentTimeMillis()).isEqualTo(1000L);
            assertThat(clock.nanoTime()).isEqualTo(1000L * 1_000_000);
        }

        @Test void fixedClockAdvance() {
            com.authx.sdk.spi.SdkClock.Fixed clock = new SdkClock.Fixed(1000L);
            clock.advanceMs(500);
            assertThat(clock.currentTimeMillis()).isEqualTo(1500L);
            assertThat(clock.nanoTime()).isEqualTo(1500L * 1_000_000);
        }

        @Test void fixedClockSetMillis() {
            com.authx.sdk.spi.SdkClock.Fixed clock = new SdkClock.Fixed(1000L);
            clock.setMillis(5000L);
            assertThat(clock.currentTimeMillis()).isEqualTo(5000L);
            assertThat(clock.nanoTime()).isEqualTo(5000L * 1_000_000);
        }
    }

    // ---- SdkInterceptor default methods ----
    @Nested class SdkInterceptorTest {
        @Test void operationContextFields() {
            com.authx.sdk.spi.SdkInterceptor.OperationContext ctx = new SdkInterceptor.OperationContext(
                SdkAction.CHECK, "document", "doc-1", "view", "user", "alice");

            assertThat(ctx.action()).isEqualTo(SdkAction.CHECK);
            assertThat(ctx.resourceType()).isEqualTo("document");
            assertThat(ctx.resourceId()).isEqualTo("doc-1");
            assertThat(ctx.permission()).isEqualTo("view");
            assertThat(ctx.subjectType()).isEqualTo("user");
            assertThat(ctx.subjectId()).isEqualTo("alice");
            assertThat(ctx.hasError()).isFalse();
        }

        @Test void operationContextMutableFields() {
            com.authx.sdk.spi.SdkInterceptor.OperationContext ctx = new SdkInterceptor.OperationContext(
                SdkAction.WRITE, "doc", "1", "edit", "user", "bob");
            ctx.setDurationMs(42);
            ctx.setResult("SUCCESS");
            assertThat(ctx.durationMs()).isEqualTo(42);
            assertThat(ctx.result()).isEqualTo("SUCCESS");
            assertThat(ctx.error()).isNull();

            java.lang.RuntimeException err = new RuntimeException("oops");
            ctx.setError(err);
            assertThat(ctx.hasError()).isTrue();
            assertThat(ctx.error()).isSameAs(err);
        }

        @Test void operationContextTypedAttributes() {
            com.authx.sdk.spi.SdkInterceptor.OperationContext ctx = new SdkInterceptor.OperationContext(
                SdkAction.CHECK, "doc", "1", "view", "user", "a");
            com.authx.sdk.spi.AttributeKey<java.lang.String> key = AttributeKey.withDefault("test-key", String.class, "default-val");

            assertThat(ctx.attr(key)).isEqualTo("default-val");

            ctx.attr(key, "custom-val");
            assertThat(ctx.attr(key)).isEqualTo("custom-val");
        }
    }

    // ---- TelemetrySink ----
    @Nested class TelemetrySinkTest {
        @Test void noopDoesNotThrow() {
            assertThatNoException().isThrownBy(() ->
                TelemetrySink.NOOP.send(java.util.List.of(java.util.Map.of("action", "CHECK"))));
        }
    }
}
