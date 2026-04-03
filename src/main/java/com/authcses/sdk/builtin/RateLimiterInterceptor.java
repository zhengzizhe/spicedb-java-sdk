package com.authcses.sdk.builtin;

import com.authcses.sdk.event.SdkEvent;
import com.authcses.sdk.event.SdkEventBus;
import com.authcses.sdk.spi.SdkInterceptor;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side rate limiter using token bucket algorithm with CAS.
 * Thread-safe under high concurrency.
 */
public class RateLimiterInterceptor implements SdkInterceptor {

    private final long maxPerSecond;
    private final SdkEventBus eventBus;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillNanos;

    public RateLimiterInterceptor(long maxPerSecond, SdkEventBus eventBus) {
        this.maxPerSecond = maxPerSecond;
        this.eventBus = eventBus;
        this.tokens = new AtomicLong(maxPerSecond);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    @Override
    public void before(OperationContext ctx) {
        refill();
        // CAS loop: atomically decrement only if > 0
        while (true) {
            long current = tokens.get();
            if (current <= 0) {
                if (eventBus != null) {
                    eventBus.fire(SdkEvent.RATE_LIMITED, "Rate limited: " + ctx.action());
                }
                throw new com.authcses.sdk.exception.AuthCsesException(
                        "Rate limited: max " + maxPerSecond + " requests/second exceeded");
            }
            if (tokens.compareAndSet(current, current - 1)) {
                return; // acquired
            }
            // CAS failed, retry
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsed = now - last;

        if (elapsed >= 1_000_000_000L) {
            if (lastRefillNanos.compareAndSet(last, now)) {
                long refill = (elapsed / 1_000_000_000L) * maxPerSecond;
                tokens.updateAndGet(current -> Math.min(current + refill, maxPerSecond));
            }
        }
    }
}
