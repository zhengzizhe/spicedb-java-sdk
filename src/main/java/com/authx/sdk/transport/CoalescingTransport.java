package com.authx.sdk.transport;

import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Request coalescing: concurrent identical check() calls share one gRPC round-trip.
 * Key includes consistency level — different consistency requirements are NOT coalesced.
 *
 * <p>Only coalesces check() (most common hot-path). Other operations pass through.
 */
public class CoalescingTransport extends ForwardingTransport {

    /** Structured coalescing key — uses record equals/hashCode, avoids String allocation. */
    private record CoalescingKey(ResourceRef resource, Permission permission, SubjectRef subject, Consistency consistency) {}

    private final SdkTransport delegate;
    private final SdkMetrics metrics;
    private final ConcurrentHashMap<CoalescingKey, CompletableFuture<CheckResult>> inflight = new ConcurrentHashMap<>();

    public CoalescingTransport(SdkTransport delegate, SdkMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    public CoalescingTransport(SdkTransport delegate) {
        this(delegate, null);
    }

    @Override
    protected SdkTransport delegate() {
        return delegate;
    }

    @Override
    public CheckResult check(CheckRequest request) {
        com.authx.sdk.transport.CoalescingTransport.CoalescingKey key = new CoalescingKey(request.resource(), request.permission(), request.subject(), request.consistency());

        // Try to be the "owner" of this request
        CompletableFuture<CheckResult> myFuture = new CompletableFuture<>();
        CompletableFuture<CheckResult> existing = inflight.putIfAbsent(key, myFuture);

        if (existing != null) {
            // Another thread is already executing this exact request — wait for its result.
            //
            // F12-1: use get(timeout, unit) rather than orTimeout(...).join(). Both
            // enforce the 30s budget, but get() responds to Thread.interrupt() —
            // critical for HTTP request paths where a client disconnect interrupts
            // the worker thread. join() would ignore the interrupt and keep the
            // worker pinned until the 30s budget elapses, wasting capacity.
            if (metrics != null) metrics.recordCoalesced();
            try {
                return existing.get(30, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new com.authx.sdk.exception.AuthxTimeoutException(
                        "Coalesced check request interrupted while waiting for the in-flight owner");
            } catch (java.util.concurrent.TimeoutException te) {
                throw new com.authx.sdk.exception.AuthxTimeoutException(
                        "Coalesced check request timed out after 30s");
            } catch (java.util.concurrent.ExecutionException ee) {
                // Owner's exception is wrapped in ExecutionException. Unwrap it so
                // callers see the original RuntimeException (or SdkException) they'd
                // see if they'd made the call themselves.
                Throwable cause = ee.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException("Coalesced check failed", cause);
            }
        }

        // We are the owner — execute the call, share result with all waiters.
        //
        // SR:C3 — Eviction ordering:
        //
        //   Success path: complete future FIRST (so waiters already at
        //   existing.get() get the result), then remove from inflight in
        //   finally. A new arrival between complete() and finally's remove()
        //   finds our completed future via putIfAbsent and gets the result
        //   immediately — safe reuse.
        //
        //   Failure path: MUST evict BEFORE publishing the exception. If we
        //   evict-after, a new arrival between completeExceptionally() and
        //   remove() would find our failed future via putIfAbsent and receive
        //   our exception for a call it did not participate in — ghost failure
        //   propagation. Evicting first means the new arrival gets null from
        //   putIfAbsent and starts its own call, which is correct semantics.
        try {
            com.authx.sdk.model.CheckResult result = delegate.check(request);
            myFuture.complete(result);
            return result;
        } catch (Exception e) {
            // SR:C3 — evict before publishing (see comment above).
            inflight.remove(key, myFuture);
            myFuture.completeExceptionally(e);
            throw e;
        } finally {
            // Success path cleanup. On the failure path this is a no-op because
            // the catch block already removed. remove(key, value) is safe to
            // call twice — the second call finds no matching value and returns
            // false without error.
            inflight.remove(key, myFuture);
        }
    }

    @Override
    public void close() {
        inflight.clear();
        delegate.close();
    }

}
