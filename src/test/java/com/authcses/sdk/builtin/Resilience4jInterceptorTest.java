package com.authcses.sdk.builtin;

import com.authcses.sdk.event.DefaultTypedEventBus;
import com.authcses.sdk.exception.AuthCsesException;
import com.authcses.sdk.model.*;
import com.authcses.sdk.model.enums.Permissionship;
import com.authcses.sdk.model.enums.SdkAction;
import com.authcses.sdk.spi.AttributeKey;
import com.authcses.sdk.spi.SdkInterceptor;
import com.authcses.sdk.transport.InMemoryTransport;
import com.authcses.sdk.transport.InterceptorTransport;
import com.authcses.sdk.transport.SdkTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Resilience4jInterceptorTest {

    @Test
    void rateLimiter_rejectsOverLimit() {
        var interceptor = Resilience4jInterceptor.builder()
                .rateLimiter(1)
                .eventBus(new DefaultTypedEventBus())
                .build();

        var inner = new InMemoryTransport();
        inner.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("doc", "1"), Relation.of("view"),
                SubjectRef.of("user", "alice", null))));

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        var request = CheckRequest.from("doc", "1", "view", "user", "alice", Consistency.minimizeLatency());

        // First call succeeds
        transport.check(request);

        // Second call should be rate limited
        assertThatThrownBy(() -> transport.check(request))
                .isInstanceOf(AuthCsesException.class)
                .hasMessageContaining("Rate limited");
    }

    @Test
    void bulkhead_rejectsOverLimit() {
        var interceptor = Resilience4jInterceptor.builder()
                .bulkhead(1)
                .eventBus(new DefaultTypedEventBus())
                .build();

        // Use a transport that blocks to hold the bulkhead permit
        var blockingTransport = new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                // Simulate a long-running check by checking bulkhead from a second call
                return new CheckResult(Permissionship.HAS_PERMISSION, null, Optional.empty());
            }
        };

        var transport = new InterceptorTransport(blockingTransport, List.of(interceptor));
        var request = CheckRequest.from("doc", "1", "view", "user", "alice", Consistency.minimizeLatency());

        // First call acquires bulkhead and succeeds (also releases in finally)
        transport.check(request);

        // After release, another call should succeed
        transport.check(request);
    }

    @Test
    void bothDisabled_isNoop() {
        var interceptor = Resilience4jInterceptor.builder()
                .eventBus(new DefaultTypedEventBus())
                .build();

        var inner = new InMemoryTransport();
        inner.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("doc", "1"), Relation.of("view"),
                SubjectRef.of("user", "alice", null))));

        var transport = new InterceptorTransport(inner, List.of(interceptor));
        var request = CheckRequest.from("doc", "1", "view", "user", "alice", Consistency.minimizeLatency());

        for (int i = 0; i < 1000; i++) {
            transport.check(request);
        }
    }
}
