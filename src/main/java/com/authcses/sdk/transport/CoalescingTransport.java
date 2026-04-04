package com.authcses.sdk.transport;

import com.authcses.sdk.metrics.SdkMetrics;
import com.authcses.sdk.model.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request coalescing: concurrent identical check() calls share one gRPC round-trip.
 * Key includes consistency level — different consistency requirements are NOT coalesced.
 *
 * <p>Only coalesces check() (most common hot-path). Other operations pass through.
 */
public class CoalescingTransport extends ForwardingTransport {

    private final SdkTransport delegate;
    private final SdkMetrics metrics;
    private final ConcurrentHashMap<String, CompletableFuture<CheckResult>> inflight = new ConcurrentHashMap<>();

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
        String key = coalescingKey(request);

        // Try to be the "owner" of this request
        CompletableFuture<CheckResult> myFuture = new CompletableFuture<>();
        CompletableFuture<CheckResult> existing = inflight.putIfAbsent(key, myFuture);

        if (existing != null) {
            // Another thread is already executing this exact request — wait for its result
            if (metrics != null) metrics.recordCoalesced();
            try {
                return existing.join();
            } catch (java.util.concurrent.CompletionException ce) {
                if (ce.getCause() instanceof RuntimeException re) throw re;
                throw ce;
            }
        }

        // We are the owner — execute the call, share result with all waiters
        try {
            var result = delegate.check(request);
            myFuture.complete(result);
            return result;
        } catch (Exception e) {
            myFuture.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, myFuture); // only remove if still ours
        }
    }

    @Override
    public void close() {
        inflight.clear();
        delegate.close();
    }

    private static String coalescingKey(CheckRequest request) {
        String consistencyKey = switch (request.consistency()) {
            case Consistency.MinimizeLatency ignored -> "min";
            case Consistency.Full ignored -> "full";
            case Consistency.AtLeast al -> "al:" + al.zedToken();
            case Consistency.AtExactSnapshot aes -> "aes:" + aes.zedToken();
        };
        return request.resource().type() + ":" + request.resource().id() + "#" +
                request.permission().name() + "@" +
                request.subject().type() + ":" + request.subject().id() + "!" + consistencyKey;
    }
}
