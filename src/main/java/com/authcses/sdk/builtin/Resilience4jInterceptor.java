package com.authcses.sdk.builtin;

import com.authcses.sdk.event.DefaultTypedEventBus;
import com.authcses.sdk.event.SdkTypedEvent;
import com.authcses.sdk.event.TypedEventBus;
import com.authcses.sdk.exception.AuthCsesException;
import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.GrantResult;
import com.authcses.sdk.spi.AttributeKey;
import com.authcses.sdk.spi.SdkInterceptor;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

import java.time.Duration;
import java.time.Instant;

public class Resilience4jInterceptor implements SdkInterceptor {

    private static final AttributeKey<Boolean> BULKHEAD_ACQUIRED =
            AttributeKey.of("bulkhead_acquired", Boolean.class);

    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final TypedEventBus eventBus;

    private Resilience4jInterceptor(RateLimiter rateLimiter, Bulkhead bulkhead, TypedEventBus eventBus) {
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
        this.eventBus = eventBus != null ? eventBus : new DefaultTypedEventBus();
    }

    @Override
    public CheckResult interceptCheck(CheckChain chain) {
        var ctx = chain.operationContext();
        acquirePermissions(ctx);
        try {
            return chain.proceed(chain.request());
        } finally {
            releasePermissions(ctx);
        }
    }

    @Override
    public GrantResult interceptWrite(WriteChain chain) {
        var ctx = chain.operationContext();
        acquirePermissions(ctx);
        try {
            return chain.proceed(chain.request());
        } finally {
            releasePermissions(ctx);
        }
    }

    private void acquirePermissions(OperationContext ctx) {
        // Rate limiter first — waitForPermission() throws RequestNotPermitted on rejection
        if (rateLimiter != null) {
            try {
                RateLimiter.waitForPermission(rateLimiter);
            } catch (io.github.resilience4j.ratelimiter.RequestNotPermitted e) {
                eventBus.publish(new SdkTypedEvent.RateLimited(Instant.now(), ctx.action().name()));
                throw new AuthCsesException("Rate limited: max requests/second exceeded");
            }
        }
        if (bulkhead != null) {
            if (!bulkhead.tryAcquirePermission()) {
                eventBus.publish(new SdkTypedEvent.BulkheadRejected(Instant.now(), ctx.action().name()));
                throw new AuthCsesException("Bulkhead rejected: max concurrent requests exceeded");
            }
            ctx.attr(BULKHEAD_ACQUIRED, true);
        }
    }

    private void releasePermissions(OperationContext ctx) {
        if (bulkhead != null) {
            Boolean acquired = ctx.attr(BULKHEAD_ACQUIRED);
            if (acquired != null && acquired) {
                bulkhead.releasePermission();
            }
        }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private RateLimiter rateLimiter;
        private Bulkhead bulkhead;
        private TypedEventBus eventBus;

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

        public Builder eventBus(TypedEventBus eventBus) { this.eventBus = eventBus; return this; }

        public Resilience4jInterceptor build() {
            return new Resilience4jInterceptor(rateLimiter, bulkhead, eventBus);
        }
    }
}
