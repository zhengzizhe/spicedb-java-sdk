package com.authx.sdk.spi;

import com.authx.sdk.model.enums.SdkAction;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SpiAdditionalTest {

    // ---- DuplicateDetector ----
    @Nested class DuplicateDetectorTest {
        @Test void noopAlwaysReturnsTrue() {
            var detector = DuplicateDetector.<String>noop();
            assertThat(detector.tryProcess("key1")).isTrue();
            assertThat(detector.tryProcess("key1")).isTrue(); // same key still true
            assertThat(detector.tryProcess(null)).isTrue();
        }

        @Test void lruCreatesWorkingDetector() {
            // Caffeine should be on classpath in this project
            var detector = DuplicateDetector.<String>lru(100, java.time.Duration.ofMinutes(1));
            assertThat(detector.tryProcess("key1")).isTrue();
            assertThat(detector.tryProcess("key1")).isFalse(); // duplicate
            assertThat(detector.tryProcess("key2")).isTrue();
        }
    }

    // ---- SdkClock ----
    @Nested class SdkClockTest {
        @Test void systemClockReturnsReasonableValues() {
            long millis = SdkClock.SYSTEM.currentTimeMillis();
            assertThat(millis).isGreaterThan(0);

            long nanos = SdkClock.SYSTEM.nanoTime();
            assertThat(nanos).isGreaterThan(0);
        }

        @Test void fixedClock() {
            var clock = new SdkClock.Fixed(1000L);
            assertThat(clock.currentTimeMillis()).isEqualTo(1000L);
            assertThat(clock.nanoTime()).isEqualTo(1000L * 1_000_000);
        }

        @Test void fixedClockAdvance() {
            var clock = new SdkClock.Fixed(1000L);
            clock.advanceMs(500);
            assertThat(clock.currentTimeMillis()).isEqualTo(1500L);
            assertThat(clock.nanoTime()).isEqualTo(1500L * 1_000_000);
        }

        @Test void fixedClockSetMillis() {
            var clock = new SdkClock.Fixed(1000L);
            clock.setMillis(5000L);
            assertThat(clock.currentTimeMillis()).isEqualTo(5000L);
            assertThat(clock.nanoTime()).isEqualTo(5000L * 1_000_000);
        }
    }

    // ---- SdkInterceptor default methods ----
    @Nested class SdkInterceptorTest {
        @Test void operationContextFields() {
            var ctx = new SdkInterceptor.OperationContext(
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
            var ctx = new SdkInterceptor.OperationContext(
                SdkAction.WRITE, "doc", "1", "edit", "user", "bob");
            ctx.setDurationMs(42);
            ctx.setResult("SUCCESS");
            assertThat(ctx.durationMs()).isEqualTo(42);
            assertThat(ctx.result()).isEqualTo("SUCCESS");
            assertThat(ctx.error()).isNull();

            var err = new RuntimeException("oops");
            ctx.setError(err);
            assertThat(ctx.hasError()).isTrue();
            assertThat(ctx.error()).isSameAs(err);
        }

        @Test void operationContextTypedAttributes() {
            var ctx = new SdkInterceptor.OperationContext(
                SdkAction.CHECK, "doc", "1", "view", "user", "a");
            var key = AttributeKey.withDefault("test-key", String.class, "default-val");

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
