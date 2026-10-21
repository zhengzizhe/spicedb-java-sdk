package com.authx.sdk.transport;

import com.authx.sdk.builtin.ValidationInterceptor;
import com.authx.sdk.exception.AuthxTimeoutException;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.*;
import com.authx.sdk.policy.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuthorizationConsistencyRegressionTest {
    abstract static class Transport extends ForwardingTransport {
        @Override protected SdkTransport delegate() { throw new UnsupportedOperationException(); }
    }

    static CheckRequest request(Consistency consistency, Map<String, Object> context) {
        return new CheckRequest(ResourceRef.of("resource", "r"), Permission.of("use"),
                SubjectRef.of("member", "m", null), consistency, context);
    }

    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @Test void distinctConditionsMustNeverShareAnAllowDecision() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Transport raw = new Transport() {
            @Override public CheckResult check(CheckRequest request) {
                calls.incrementAndGet();
                if (Boolean.TRUE.equals(request.caveatContext().get("allowed"))) {
                    entered.countDown(); await(release); return CheckResult.allowed("t");
                }
                return CheckResult.denied("t");
            }
        };
        CoalescingTransport transport = new CoalescingTransport(raw);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<CheckResult> owner = pool.submit(() -> transport.check(request(Consistency.atLeast("t"), Map.of("allowed", true))));
            await(entered);
            try {
                Future<CheckResult> other = pool.submit(() -> transport.check(request(Consistency.atLeast("t"), Map.of("allowed", false))));
                assertThat(other.get(1, TimeUnit.SECONDS).hasPermission()).isFalse();
            } finally { release.countDown(); }
            assertThat(owner.get(1, TimeUnit.SECONDS).hasPermission()).isTrue();
            assertThat(calls).hasValue(2);
        }
    }

    @Test void newTokenMustBeResolvedBeforeJoiningAnInflightRequest() throws Exception {
        TokenTracker tracker = new TokenTracker();
        tracker.recordWrite("resource", "before");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Transport raw = new Transport() {
            @Override public CheckResult check(CheckRequest request) {
                String token = ((Consistency.AtLeast) request.consistency()).zedToken();
                if (token.equals("before")) { entered.countDown(); await(release); return CheckResult.allowed(token); }
                return CheckResult.denied(token);
            }
        };
        // Production order: resolve effective consistency outside request coalescing.
        PolicyAwareConsistencyTransport transport = new PolicyAwareConsistencyTransport(
                new CoalescingTransport(raw), PolicyRegistry.withDefaults(), tracker);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<CheckResult> owner = pool.submit(() -> transport.check(request(Consistency.minimizeLatency(), null)));
            await(entered);
            tracker.recordWrite("resource", "revoked");
            try {
                Future<CheckResult> next = pool.submit(() -> transport.check(request(Consistency.minimizeLatency(), null)));
                assertThat(next.get(1, TimeUnit.SECONDS).hasPermission()).isFalse();
            } finally { release.countDown(); }
            assertThat(owner.get(1, TimeUnit.SECONDS).hasPermission()).isTrue();
        }
    }

    @Test void headReadsMustNotShareAnOlderInflightSnapshot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Transport raw = new Transport() {
            @Override public CheckResult check(CheckRequest request) {
                if (calls.incrementAndGet() == 1) { entered.countDown(); await(release); return CheckResult.allowed("old"); }
                return CheckResult.denied("new");
            }
        };
        CoalescingTransport transport = new CoalescingTransport(raw);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<CheckResult> owner = pool.submit(() -> transport.check(request(Consistency.full(), null)));
            await(entered);
            try { assertThat(transport.check(request(Consistency.full(), null)).hasPermission()).isFalse(); }
            finally { release.countDown(); }
            owner.get(1, TimeUnit.SECONDS);
        }
    }

    @Test void identicalBoundedChecksStillCoalesceAndFollowersKeepTheirDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SdkMetrics metrics = new SdkMetrics();
        CoalescingTransport transport = new CoalescingTransport(new Transport() {
            @Override public CheckResult check(CheckRequest request) { entered.countDown(); await(release); return CheckResult.allowed("t"); }
        }, metrics);
        CheckRequest request = request(Consistency.atLeast("t"), Map.of("nested", Map.of("x", 1)));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<CheckResult> owner = pool.submit(() -> transport.check(request)); await(entered);
            PolicyRegistry policies = PolicyRegistry.builder().defaultPolicy(ResourcePolicy.builder()
                    .timeout(Duration.ofMillis(25)).build()).build();
            try {
                assertThatThrownBy(() -> new DeadlineTransport(transport, policies, 500).check(request))
                        .isInstanceOf(AuthxTimeoutException.class);
                assertThat(metrics.coalescedRequests()).isEqualTo(1);
            } finally { release.countDown(); }
            assertThat(owner.get(1, TimeUnit.SECONDS).hasPermission()).isTrue();
        }
    }

    @Test void nestedConditionsAreFrozenBeforeDeferredExecution() {
        List<Object> list = new ArrayList<>(List.of("before"));
        Map<String, Object> nested = new HashMap<>(Map.of("values", list));
        CheckRequest request = request(Consistency.full(), Map.of("nested", nested));
        list.set(0, "after"); nested.put("new", true);
        assertThat(request.caveatContext()).isEqualTo(Map.of("nested", Map.of("values", List.of("before"))));
    }

    @Test void validationErrorsMustNotBeIgnoredAsOptionalInterceptorFailures() {
        AtomicInteger calls = new AtomicInteger();
        InterceptorTransport transport = new InterceptorTransport(new Transport() {
            @Override public CheckResult check(CheckRequest request) { calls.incrementAndGet(); return CheckResult.allowed("t"); }
        }, List.of(new ValidationInterceptor()));
        CheckRequest invalid = new CheckRequest(ResourceRef.of("resource", "r"), Permission.of("BadPermission"),
                SubjectRef.of("user", "u", null), Consistency.full(), null);
        assertThatThrownBy(() -> transport.check(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void resourcePolicyTimeoutIsAnOperationBudget() {
        PolicyRegistry policies = PolicyRegistry.builder().defaultPolicy(ResourcePolicy.builder()
                .timeout(Duration.ofMillis(25)).build()).build();
        DeadlineTransport transport = new DeadlineTransport(new Transport() {
            @Override public CheckResult check(CheckRequest request) {
                long remaining = io.grpc.Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS);
                assertThat(remaining).isBetween(0L, 25L);
                await(new CountDownLatch(0));
                return CheckResult.denied("t");
            }
        }, policies, 1000);
        assertThat(transport.check(request(Consistency.full(), null)).hasPermission()).isFalse();
    }

    @Test void mixedBatchUsesTheTightestResourceTimeout() {
        PolicyRegistry policies = PolicyRegistry.builder().forResourceType("tight", ResourcePolicy.builder()
                .timeout(Duration.ofMillis(20)).build()).build();
        DeadlineTransport transport = new DeadlineTransport(new Transport() {
            @Override public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
                assertThat(io.grpc.Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS)).isBetween(0L, 20L);
                return List.of(CheckResult.denied("t"), CheckResult.denied("t"));
            }
        }, policies, 1000);
        transport.checkBulkMulti(List.of(new SdkTransport.BulkCheckItem(ResourceRef.of("wide", "r"), Permission.of("view"), SubjectRef.parse("user:u")),
                new SdkTransport.BulkCheckItem(ResourceRef.of("tight", "r"), Permission.of("view"), SubjectRef.parse("user:u"))), Consistency.full());
    }

    @Test void permanentProtocolFailuresAreNotRetriedAndBackoffIsCapped() {
        assertThat(RetryPolicy.defaults().shouldRetry(new com.authx.sdk.exception.AuthxProtocolException("bad response"))).isFalse();
        RetryPolicy policy = RetryPolicy.builder().baseDelay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofMillis(20)).jitterFactor(1).build();
        for (int i = 0; i < 20; i++) assertThat(policy.delayForAttempt(i)).isLessThanOrEqualTo(Duration.ofMillis(20));
        assertThatThrownBy(() -> new TokenTracker().resolve(ReadConsistency.boundedStaleness(Duration.ofSeconds(1)), "r"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void cancellationStopsOnlyTheFollowerWithoutCancellingTheOwner() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SdkMetrics metrics = new SdkMetrics();
        CoalescingTransport transport = new CoalescingTransport(new Transport() {
            @Override public CheckResult check(CheckRequest request) { entered.countDown(); await(release); return CheckResult.allowed("t"); }
        }, metrics);
        CheckRequest request = request(Consistency.atLeast("t"), null);
        io.grpc.Context.CancellableContext context = io.grpc.Context.current().withCancellation();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<CheckResult> owner = pool.submit(() -> transport.check(request)); await(entered);
            Future<CheckResult> follower = pool.submit(() -> context.call(() -> transport.check(request)));
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (metrics.coalescedRequests() == 0 && System.nanoTime() < until) Thread.sleep(1);
                assertThat(metrics.coalescedRequests()).isEqualTo(1);
                context.cancel(null);
                assertThatThrownBy(() -> follower.get(500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AuthxTimeoutException.class);
            } finally { context.cancel(null); release.countDown(); }
            assertThat(owner.get(1, TimeUnit.SECONDS).hasPermission()).isTrue();
        }
    }

}
