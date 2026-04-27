package com.authx.sdk.transport;

import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.exception.AuthxConnectionException;
import com.authx.sdk.exception.CircuitBreakerOpenException;
import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import com.authx.sdk.policy.CircuitBreakerPolicy;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ResourcePolicy;
import com.authx.sdk.policy.RetryPolicy;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientTransportTest {

    private static final CheckResult OK = new CheckResult(Permissionship.HAS_PERMISSION, null, Optional.empty());

    @Test
    void happyPath_delegatesToTransport() {
        InMemoryTransport delegate = new InMemoryTransport();
        delegate.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "doc-1"),
                        Relation.of("viewer"),
                        SubjectRef.of("user", "alice", null))));
        ResilientTransport transport = new ResilientTransport(delegate, PolicyRegistry.withDefaults(), new DefaultTypedEventBus());

        CheckResult result = transport.check(CheckRequest.of("document", "doc-1", "viewer", "user", "alice", Consistency.minimizeLatency()));
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void retryOnTransientFailure() {
        AtomicInteger callCount = new AtomicInteger(0);
        PolicyRegistry policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(3).baseDelay(Duration.ofMillis(10)).build())
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .build())
                .build();

        // Delegate that fails twice then succeeds
        SdkTransport delegate = failingDelegate(callCount, 2, OK);
        ResilientTransport transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        CheckResult result = transport.check(CheckRequest.of("document", "doc-1", "view", "user", "alice", Consistency.minimizeLatency()));
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
        assertThat(callCount.get()).isEqualTo(3); // 2 failures + 1 success
    }

    @Test
    void circuitBreakerOpens_afterThreshold() {
        PolicyRegistry policy = PolicyRegistry.builder()
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

        AtomicInteger callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        ResilientTransport transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // Exhaust the sliding window
        for (int i = 0; i < 4; i++) {
            try { transport.check(CheckRequest.of("doc", "1", "view", "user", "a", Consistency.minimizeLatency())); }
            catch (Exception ignored) {}
        }

        // Next call should be rejected by circuit breaker
        assertThatThrownBy(() ->
                transport.check(CheckRequest.of("doc", "1", "view", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void failOpen_returnsHasPermission() {
        PolicyRegistry policy = PolicyRegistry.builder()
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

        AtomicInteger callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        ResilientTransport transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // Open the circuit
        for (int i = 0; i < 4; i++) {
            try { transport.check(CheckRequest.of("doc", "1", "view", "user", "a", Consistency.minimizeLatency())); }
            catch (Exception ignored) {}
        }

        // fail-open for "view"
        CheckResult result = transport.check(CheckRequest.of("doc", "1", "view", "user", "a", Consistency.minimizeLatency()));
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);

        // "edit" is NOT in fail-open set
        assertThatThrownBy(() ->
                transport.check(CheckRequest.of("doc", "1", "edit", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void perResourceTypeIsolation() {
        PolicyRegistry policy = PolicyRegistry.builder()
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

        AtomicInteger callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        ResilientTransport transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // Fail "document" breaker
        for (int i = 0; i < 4; i++) {
            try { transport.check(CheckRequest.of("document", "1", "view", "user", "a", Consistency.minimizeLatency())); }
            catch (Exception ignored) {}
        }

        // "document" circuit is open
        assertThatThrownBy(() ->
                transport.check(CheckRequest.of("document", "1", "view", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(CircuitBreakerOpenException.class);

        // "folder" circuit is still closed — should call delegate (and fail, but not with CircuitBreakerOpenException)
        assertThatThrownBy(() ->
                transport.check(CheckRequest.of("folder", "1", "view", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(AuthxConnectionException.class);
    }

    @Test
    void disabledCircuitBreaker_passesThrough() {
        PolicyRegistry policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(1).build())
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .build())
                .build();

        AtomicInteger callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        ResilientTransport transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // Even after many failures, no CircuitBreakerOpenException
        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() ->
                    transport.check(CheckRequest.of("doc", "1", "view", "user", "a", Consistency.minimizeLatency())))
                    .isInstanceOf(AuthxConnectionException.class);
        }
        assertThat(callCount.get()).isEqualTo(100);
    }

    @Test
    void retryExhaustion_feedsIntoCircuitBreaker() {
        // With CB wrapping Retry, each logical call counts as 1 CB record regardless of retries.
        // slidingWindowSize=6, failureRate=50%, minimumNumberOfCalls=6
        // → need 6 logical calls, all failing, to open the circuit (100% > 50% threshold)
        PolicyRegistry policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(3).baseDelay(Duration.ofMillis(1)).build())
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50)
                                .slidingWindowSize(6)
                                .minimumNumberOfCalls(6)
                                .waitInOpenState(Duration.ofHours(1))
                                .build())
                        .build())
                .build();

        AtomicInteger callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        ResilientTransport transport = new ResilientTransport(delegate, policy, new DefaultTypedEventBus());

        // 6 logical calls to fill the sliding window — each exhausts retries internally
        for (int i = 0; i < 6; i++) {
            try { transport.check(CheckRequest.of("doc", "1", "view", "user", "a", Consistency.minimizeLatency())); }
            catch (Exception ignored) {}
        }

        // Circuit should now be open (100% failure rate > 50% threshold)
        assertThatThrownBy(() ->
                transport.check(CheckRequest.of("doc", "1", "view", "user", "a", Consistency.minimizeLatency())))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void close_cleansUpInstances() {
        ResilientTransport transport = new ResilientTransport(new InMemoryTransport(), PolicyRegistry.withDefaults(), new DefaultTypedEventBus());
        transport.check(CheckRequest.of("document", "1", "viewer", "user", "a", Consistency.minimizeLatency()));
        transport.close(); // should not throw
    }

    @Test
    void highCardinality_breakersMapStaysBounded() throws Exception {
        // Multi-tenant regression: the previous implementation used
        // ConcurrentHashMap with no eviction strategy beyond
        // "iterator().next()" — under high cardinality the map could
        // grow unbounded and eviction was random (see audit risk #6).
        //
        // Caffeine-backed map enforces MAX_INSTANCES with W-TinyLFU,
        // and recently-accessed keys win admission against one-shot keys.
        InMemoryTransport noopDelegate = new InMemoryTransport();
        ResilientTransport transport = new ResilientTransport(
                noopDelegate, PolicyRegistry.withDefaults(), new DefaultTypedEventBus());

        // Push 5000 distinct resource types — well above the 1000 cap.
        for (int i = 0; i < 5000; i++) {
            transport.check(CheckRequest.of(
                    "type_" + i, "x", "view", "user", "a", Consistency.minimizeLatency()));
        }

        // Touch one "hot" type many times so it has a strong recency+frequency signal.
        for (int i = 0; i < 100; i++) {
            transport.check(CheckRequest.of(
                    "hot_type", "x", "view", "user", "a", Consistency.minimizeLatency()));
        }

        // Allow Caffeine's async maintenance to settle.
        Thread.sleep(200);

        Field field = ResilientTransport.class.getDeclaredField("breakers");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, ?> map =
                (ConcurrentMap<String, ?>) field.get(transport);

        // Property 1: bounded size (was unbounded with the old code if
        // the eviction loop hit a race).
        assertThat(map.size())
                .as("breakers map must respect MAX_INSTANCES bound")
                .isLessThanOrEqualTo(1100);   // small slack for Caffeine async eviction

        // Property 2: the most recently / heavily accessed type survives.
        // This is the property that was broken in the old implementation —
        // hot keys could be evicted at random while cold keys stayed.
        assertThat(map.containsKey("hot_type"))
                .as("hot type with high recent activity must survive eviction")
                .isTrue();
    }

    // ---- Helpers ----

    private SdkTransport failingDelegate(AtomicInteger callCount, int failCount, CheckResult successResult) {
        return new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                if (callCount.getAndIncrement() < failCount) {
                    throw new AuthxConnectionException("transient failure");
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
                throw new AuthxConnectionException("always fails");
            }
        };
    }
}
