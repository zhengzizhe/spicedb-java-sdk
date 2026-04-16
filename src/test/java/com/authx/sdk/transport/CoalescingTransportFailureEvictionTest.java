package com.authx.sdk.transport;

import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SR:C3 — When the coalescing leader fails, the failed future must be evicted
 * from the inflight map BEFORE the exception is published to waiters. Any
 * request that arrives AFTER the leader fails must start its own call instead
 * of inheriting the ghost failure.
 */
class CoalescingTransportFailureEvictionTest {

    private static CheckRequest req() {
        return CheckRequest.of(
                new ResourceRef("document", "doc-1"),
                new Permission("view"),
                new SubjectRef("user", "alice", null),
                Consistency.minimizeLatency());
    }

    /**
     * Stub transport: first call fails on a latch, subsequent calls succeed.
     * Tracks call count and delivers a controllable failure/success.
     */
    /** Extends InMemoryTransport so we inherit all non-check stubs. */
    static final class GatedFailingTransport extends InMemoryTransport {
        final CountDownLatch releaseFailure = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger(0);

        @Override
        public CheckResult check(CheckRequest r) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                try { releaseFailure.await(5, TimeUnit.SECONDS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                throw new com.authx.sdk.exception.AuthxConnectionException(
                        "simulated leader failure", null);
            }
            return new CheckResult(Permissionship.HAS_PERMISSION, "tok-" + call, Optional.empty());
        }
    }

    @Test
    void newcomer_after_leader_failure_starts_fresh_call() throws Exception {
        var delegate = new GatedFailingTransport();
        var coalescer = new CoalescingTransport(delegate);

        // Thread A is the leader; it will block on releaseFailure before throwing.
        var leaderErr = new CompletableFuture<Throwable>();
        var leader = new Thread(() -> {
            try {
                coalescer.check(req());
                leaderErr.complete(null);
            } catch (Throwable t) {
                leaderErr.complete(t);
            }
        }, "leader");
        leader.start();

        // Ensure leader has registered its future before releasing.
        Thread.sleep(50);

        // Release the failure, then wait briefly for the leader to propagate.
        delegate.releaseFailure.countDown();
        var lerr = leaderErr.get(2, TimeUnit.SECONDS);
        assertNotNull(lerr, "leader must have thrown");
        assertInstanceOf(com.authx.sdk.exception.AuthxConnectionException.class, lerr);

        // Now a newcomer arrives AFTER leader failed. Under the fixed eviction
        // order (evict before publish), the inflight map has already been
        // cleared, so putIfAbsent returns null and the newcomer starts a fresh
        // call — which the stub transport returns successfully (call #2).
        var newcomerResult = coalescer.check(req());
        assertEquals(Permissionship.HAS_PERMISSION, newcomerResult.permissionship(),
                "newcomer must start its own call and receive success, not ghost failure");
        assertEquals(2, delegate.calls.get(),
                "exactly two underlying calls: leader + newcomer");
    }

    @Test
    void waiter_joined_before_failure_receives_the_failure() throws Exception {
        // The complementary invariant: a waiter that putIfAbsent-finds the
        // leader's future BEFORE it fails must still receive the exception
        // (coalescing's whole point). Only the post-eviction newcomers start
        // fresh.
        var delegate = new GatedFailingTransport();
        var coalescer = new CoalescingTransport(delegate);

        var leaderErr = new CompletableFuture<Throwable>();
        var waiterErr = new CompletableFuture<Throwable>();

        var leader = new Thread(() -> {
            try { coalescer.check(req()); leaderErr.complete(null); }
            catch (Throwable t) { leaderErr.complete(t); }
        }, "leader");
        leader.start();
        Thread.sleep(20);  // leader registers the inflight future

        var waiter = new Thread(() -> {
            try { coalescer.check(req()); waiterErr.complete(null); }
            catch (Throwable t) { waiterErr.complete(t); }
        }, "waiter");
        waiter.start();
        Thread.sleep(20);  // waiter joins the inflight future

        delegate.releaseFailure.countDown();

        var lerr = leaderErr.get(2, TimeUnit.SECONDS);
        var werr = waiterErr.get(2, TimeUnit.SECONDS);
        assertNotNull(lerr);
        assertNotNull(werr,
                "waiter joined before eviction — must receive the leader's exception");
        assertEquals(1, delegate.calls.get(),
                "only the leader made a call — waiter coalesced");
    }
}
