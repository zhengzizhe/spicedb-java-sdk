package com.authx.sdk.spi;

import com.authx.sdk.model.enums.SdkAction;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AttributeKeyTest {

    static final AttributeKey<String> TRACE_ID = AttributeKey.of("traceId", String.class);
    static final AttributeKey<Long> TIMEOUT_MS = AttributeKey.withDefault("timeoutMs", Long.class, 5000L);
    static final AttributeKey<Boolean> DEBUG = AttributeKey.withDefault("debug", Boolean.class, false);

    @Test void typeSafe_setAndGet() {
        SdkInterceptor.OperationContext ctx = new SdkInterceptor.OperationContext(SdkAction.CHECK, "doc", "d1", "view", "user", "alice");
        ctx.attr(TRACE_ID, "abc-123");
        String traceId = ctx.attr(TRACE_ID); // no cast
        assertThat(traceId).isEqualTo("abc-123");
    }

    @Test void defaultValue_whenNotSet() {
        SdkInterceptor.OperationContext ctx = new SdkInterceptor.OperationContext(SdkAction.CHECK, "doc", "d1", "view", "user", "alice");
        Long timeout = ctx.attr(TIMEOUT_MS);
        assertThat(timeout).isEqualTo(5000L);
    }

    @Test void nullDefault_whenNotSet() {
        SdkInterceptor.OperationContext ctx = new SdkInterceptor.OperationContext(SdkAction.CHECK, "doc", "d1", "view", "user", "alice");
        String traceId = ctx.attr(TRACE_ID);
        assertThat(traceId).isNull();
    }

    @Test void overwrite() {
        SdkInterceptor.OperationContext ctx = new SdkInterceptor.OperationContext(SdkAction.CHECK, "doc", "d1", "view", "user", "alice");
        ctx.attr(TRACE_ID, "first");
        ctx.attr(TRACE_ID, "second");
        assertThat(ctx.attr(TRACE_ID)).isEqualTo("second");
    }

    @Test void identityEquality() {
        AttributeKey<String> key1 = AttributeKey.of("test", String.class);
        AttributeKey<String> key2 = AttributeKey.of("test", String.class);
        assertThat(key1).isNotEqualTo(key2); // identity, not name-based
    }

    @Test void defaultBoolean() {
        SdkInterceptor.OperationContext ctx = new SdkInterceptor.OperationContext(SdkAction.CHECK, "doc", "d1", "view", "user", "alice");
        assertThat(ctx.attr(DEBUG)).isFalse();
    }
}
