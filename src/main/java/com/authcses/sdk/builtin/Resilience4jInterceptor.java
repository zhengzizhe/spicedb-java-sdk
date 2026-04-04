package com.authcses.sdk.builtin;

import com.authcses.sdk.event.SdkEvent;
import com.authcses.sdk.event.SdkEventBus;
import com.authcses.sdk.exception.AuthCsesException;
import com.authcses.sdk.spi.SdkInterceptor;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

import java.time.Duration;

public class Resilience4jInterceptor implements SdkInterceptor {

    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final SdkEventBus eventBus;

    private Resilience4jInterceptor(RateLimiter rateLimiter, Bulkhead bulkhead, SdkEventBus eventBus) {
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
        this.eventBus = eventBus != null ? eventBus : new SdkEventBus();
    }

    @Override
    public void before(OperationContext ctx) {
        // Rate limiter first — waitForPermission() throws RequestNotPermitted on rejection
        if (rateLimiter != null) {
            try {
                RateLimiter.waitForPermission(rateLimiter);
            } catch (io.github.resilience4j.ratelimiter.RequestNotPermitted e) {
                eventBus.fire(SdkEvent.RATE_LIMITED, "Rate limited: " + ctx.action());
                throw new AuthCsesException("Rate limited: max requests/second exceeded");
            }
        }
        if (bulkhead != null) {
            if (!bulkhead.tryAcquirePermission()) {
                eventBus.fire(SdkEvent.BULKHEAD_REJECTED, "Bulkhead full: " + ctx.action());
                throw new AuthCsesException("Bulkhead rejected: max concurrent requests exceeded");
            }
            ctx.setAttribute("_bulkhead_acquired", true);
        }
    }

    @Override
    public void after(OperationContext ctx) {
        if (bulkhead != null) {
            Boolean acquired = ctx.getAttribute("_bulkhead_acquired");
            if (acquired != null && acquired) {
                bulkhead.releasePermission();
            }
        }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private RateLimiter rateLimiter;
        private Bulkhead bulkhead;
        private SdkEventBus eventBus;

        public Builder rateLimiter(int maxPerSecond) {
            this.rateLimiter = RateLimiter.of("authcses-sdk", RateLimiterConfig.custom()
                    .limitForPeriod(maxPerSecond)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .timeoutDuration(Duration.ZERO)
                    .build());
            return this;
        }

        public Builder bulkhead(int maxConcurrent) {
            this.bulkhead = Bulkhead.of("authcses-sdk", BulkheadConfig.custom()
                    .maxConcurrentCalls(maxConcurrent)
                    .maxWaitDuration(Duration.ZERO)
                    .build());
            return this;
        }

        public Builder eventBus(SdkEventBus eventBus) { this.eventBus = eventBus; return this; }

        public Resilience4jInterceptor build() {
            return new Resilience4jInterceptor(rateLimiter, bulkhead, eventBus);
        }
    }
}
