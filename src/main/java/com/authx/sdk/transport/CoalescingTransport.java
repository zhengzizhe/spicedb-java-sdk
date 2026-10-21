package com.authx.sdk.transport;

import com.authx.sdk.exception.AuthxTimeoutException;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Request coalescing: concurrent identical check() calls share one gRPC round-trip.
 * Key includes consistency level — different consistency requirements are NOT coalesced.
 *
 * <p>Only coalesces check() (most common hot-path). Other operations pass through.
 */
public class CoalescingTransport extends ForwardingTransport {

    /** Structured coalescing key — uses record equals/hashCode, avoids String allocation. */
    private record CoalescingKey(ResourceRef resource, Permission permission, SubjectRef subject, Consistency consistency, java.util.Map<String, Object> context) {}

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
        // A head read belongs to this invocation; reusing an older in-flight head read
        // can miss a write completed before this request began.
        if (request.consistency() instanceof Consistency.Full) return delegate.check(request);
        CoalescingKey key = new CoalescingKey(request.resource(), request.permission(),
                request.subject(), request.consistency(), request.caveatContext());

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
            io.grpc.Context caller = io.grpc.Context.current();
            CompletableFuture<CheckResult> waiting = new CompletableFuture<>();
            existing.whenComplete((result, failure) -> {
                if (failure == null) waiting.complete(result);
                else waiting.completeExceptionally(failure);
            });
            io.grpc.Context.CancellationListener cancelled = ignored -> waiting.completeExceptionally(
                    new AuthxTimeoutException("Coalesced request cancelled"));
            caller.addListener(cancelled, Runnable::run);
            try {
                io.grpc.Deadline deadline = io.grpc.Context.current().getDeadline();
                long waitNanos = deadline == null ? TimeUnit.SECONDS.toNanos(30)
                        : Math.min(TimeUnit.SECONDS.toNanos(30), deadline.timeRemaining(TimeUnit.NANOSECONDS));
                if (io.grpc.Context.current().isCancelled() || waitNanos <= 0) {
                    throw new AuthxTimeoutException("Coalesced request deadline expired or was cancelled");
                }
                return waiting.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new AuthxTimeoutException(
                        "Coalesced check request interrupted while waiting for the in-flight owner");
            } catch (TimeoutException te) {
                throw new AuthxTimeoutException(
                        "Coalesced check request timed out after 30s");
            } catch (ExecutionException ee) {
                // Owner's exception is wrapped in ExecutionException. Unwrap it so
                // callers see the original RuntimeException (or SdkException) they'd
                // see if they'd made the call themselves.
                Throwable cause = ee.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException("Coalesced check failed", cause);
            } finally {
                caller.removeListener(cancelled);
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
            CheckResult result = delegate.check(request);
            myFuture.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
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
        inflight.values().forEach(future -> future.completeExceptionally(
                new AuthxTimeoutException("SDK closed while waiting for a coalesced request")));
        inflight.clear();
        delegate.close();
    }

}
