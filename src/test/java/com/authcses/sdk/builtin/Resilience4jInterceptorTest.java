package com.authcses.sdk.builtin;

import com.authcses.sdk.event.SdkEventBus;
import com.authcses.sdk.exception.AuthCsesException;
import com.authcses.sdk.spi.SdkInterceptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Resilience4jInterceptorTest {

    @Test
    void rateLimiter_rejectsOverLimit() {
        var interceptor = Resilience4jInterceptor.builder()
                .rateLimiter(1)
                .eventBus(new SdkEventBus())
                .build();

        var ctx = new SdkInterceptor.OperationContext("CHECK", "doc", "1", "view", "user", "alice");
        interceptor.before(ctx);

        assertThatThrownBy(() -> interceptor.before(ctx))
                .isInstanceOf(AuthCsesException.class)
                .hasMessageContaining("Rate limited");
    }

    @Test
    void bulkhead_rejectsOverLimit() {
        var interceptor = Resilience4jInterceptor.builder()
                .bulkhead(1)
                .eventBus(new SdkEventBus())
                .build();

        var ctx1 = new SdkInterceptor.OperationContext("CHECK", "doc", "1", "view", "user", "alice");
        interceptor.before(ctx1);

        var ctx2 = new SdkInterceptor.OperationContext("CHECK", "doc", "2", "view", "user", "bob");
        assertThatThrownBy(() -> interceptor.before(ctx2))
                .isInstanceOf(AuthCsesException.class)
                .hasMessageContaining("Bulkhead rejected");

        interceptor.after(ctx1);
        interceptor.before(ctx2); // now succeeds
    }

    @Test
    void bothDisabled_isNoop() {
        var interceptor = Resilience4jInterceptor.builder()
                .eventBus(new SdkEventBus())
                .build();

        var ctx = new SdkInterceptor.OperationContext("CHECK", "doc", "1", "view", "user", "alice");
        for (int i = 0; i < 1000; i++) {
            interceptor.before(ctx);
            interceptor.after(ctx);
        }
    }
}
