package com.authcses.sdk.transport;

import com.authcses.sdk.event.DefaultTypedEventBus;
import com.authcses.sdk.exception.AuthCsesConnectionException;
import com.authcses.sdk.exception.CircuitBreakerOpenException;
import com.authcses.sdk.model.*;
import com.authcses.sdk.model.enums.Permissionship;
import com.authcses.sdk.policy.CircuitBreakerPolicy;
import com.authcses.sdk.policy.PolicyRegistry;
import com.authcses.sdk.policy.ResourcePolicy;
import com.authcses.sdk.policy.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientTransportTest {

    private static final CheckResult OK = new CheckResult(Permissionship.HAS_PERMISSION, null, Optional.empty());

    @Test
    void happyPath_delegatesToTransport() {
        var delegate = new InMemoryTransport();
        delegate.writeRelationships(java.util.List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "doc-1"),
                        Relation.of("viewer"),
                        SubjectRef.of("user", "alice", null))));
        var transport = new ResilientTransport(delegate, PolicyRegistry.withDefaults(), new DefaultTypedEventBus());

        var result = transport.check(CheckRequest.from("document", "doc-1", "viewer", "user", "alice", Consistency.minimizeLatency()));
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void retryOnTransientFailure() {
        var callCount = new AtomicInteger(0);
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(3).baseDelay(Duration.ofMillis(10)).build())
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .build())
                .build();

        // Delegate that fails twice then succeeds
        SdkTransport delegate = failingDelegate(callCount, 2, OK);
        var transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        var result = transport.check(CheckRequest.from("document", "doc-1", "view", "user", "alice", Consistency.minimizeLatency()));
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
        assertThat(callCount.get()).isEqualTo(3); // 2 failures + 1 success
    }

    @Test
    void circuitBreakerOpens_afterThreshold() {
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(1).build())
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50)
                                .slidingWindowSize(4)
                                .minimumNumberOfCalls(4)
                                .waitInOpenState(Duration.ofHours(1))
                                .build())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // Exhaust the sliding window
        for (int i = 0; i < 4; i++) {
            try { transport.check(CheckRequest.from("doc", "1", "view", "user", "a", Consistency.minimizeLatency())); }
            catch (Exception ignored) {}
        }

        // Next call should be rejected by circuit breaker
        assertThatThrownBy(() ->
                transport.check(CheckRequest.from("doc", "1", "view", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void failOpen_returnsHasPermission() {
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(1).build())
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50)
                                .slidingWindowSize(4)
                                .minimumNumberOfCalls(4)
                                .waitInOpenState(Duration.ofHours(1))
                                .failOpenPermissions(Set.of("view"))
                                .build())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // Open the circuit
        for (int i = 0; i < 4; i++) {
            try { transport.check(CheckRequest.from("doc", "1", "view", "user", "a", Consistency.minimizeLatency())); }
            catch (Exception ignored) {}
        }

        // fail-open for "view"
        var result = transport.check(CheckRequest.from("doc", "1", "view", "user", "a", Consistency.minimizeLatency()));
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);

        // "edit" is NOT in fail-open set
        assertThatThrownBy(() ->
                transport.check(CheckRequest.from("doc", "1", "edit", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void perResourceTypeIsolation() {
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(1).build())
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50)
                                .slidingWindowSize(4)
                                .minimumNumberOfCalls(4)
                                .waitInOpenState(Duration.ofHours(1))
                                .build())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // Fail "document" breaker
        for (int i = 0; i < 4; i++) {
            try { transport.check(CheckRequest.from("document", "1", "view", "user", "a", Consistency.minimizeLatency())); }
            catch (Exception ignored) {}
        }

        // "document" circuit is open
        assertThatThrownBy(() ->
                transport.check(CheckRequest.from("document", "1", "view", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(CircuitBreakerOpenException.class);

        // "folder" circuit is still closed — should call delegate (and fail, but not with CircuitBreakerOpenException)
        assertThatThrownBy(() ->
                transport.check(CheckRequest.from("folder", "1", "view", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(AuthCsesConnectionException.class);
    }

    @Test
    void disabledCircuitBreaker_passesThrough() {
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(1).build())
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // Even after many failures, no CircuitBreakerOpenException
        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() ->
                    transport.check(CheckRequest.from("doc", "1", "view", "user", "a", Consistency.minimizeLatency())))
                    .isInstanceOf(AuthCsesConnectionException.class);
        }
        assertThat(callCount.get()).isEqualTo(100);
    }

    @Test
    void retryExhaustion_feedsIntoCircuitBreaker() {
        // maxAttempts=3 means 3 total attempts per logical call
        // slidingWindowSize=4, failureRate=50% → 2 failures out of 4 opens the circuit
        // Each logical call generates 3 CB failure recordings (3 retry attempts)
        // So after 2 logical calls = 6 CB recordings, well above threshold
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(3).baseDelay(Duration.ofMillis(1)).build())
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50)
                                .slidingWindowSize(10)
                                .minimumNumberOfCalls(6)
                                .waitInOpenState(Duration.ofHours(1))
                                .build())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // 2 logical calls, each generating 3 retry attempts = 6 CB failure recordings
        for (int i = 0; i < 2; i++) {
            try { transport.check(CheckRequest.from("doc", "1", "view", "user", "a", Consistency.minimizeLatency())); }
            catch (Exception ignored) {}
        }

        // Circuit should now be open
        assertThatThrownBy(() ->
                transport.check(CheckRequest.from("doc", "1", "view", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void close_cleansUpInstances() {
        var transport = new ResilientTransport(new InMemoryTransport(), PolicyRegistry.withDefaults(), new DefaultTypedEventBus());
        transport.check(CheckRequest.from("document", "1", "viewer", "user", "a", Consistency.minimizeLatency()));
        transport.close(); // should not throw
    }

    // ---- Helpers ----

    private SdkTransport failingDelegate(AtomicInteger callCount, int failCount, CheckResult successResult) {
        return new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                if (callCount.getAndIncrement() < failCount) {
                    throw new AuthCsesConnectionException("transient failure");
                }
                return successResult;
            }
        };
    }

    private SdkTransport alwaysFailingDelegate(AtomicInteger callCount) {
        return new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                callCount.incrementAndGet();
                throw new AuthCsesConnectionException("always fails");
            }
        };
    }
}
