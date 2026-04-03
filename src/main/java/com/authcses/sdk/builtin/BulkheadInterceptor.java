package com.authcses.sdk.builtin;

import com.authcses.sdk.event.SdkEvent;
import com.authcses.sdk.event.SdkEventBus;
import com.authcses.sdk.spi.SdkInterceptor;

import java.util.concurrent.Semaphore;

/**
 * Client-side bulkhead: limits maximum concurrent requests to SpiceDB.
 * Prevents thread pool exhaustion under burst load.
 *
 * <pre>
 * .addInterceptor(new BulkheadInterceptor(200, eventBus))  // max 200 concurrent
 * </pre>
 */
public class BulkheadInterceptor implements SdkInterceptor {

    private final Semaphore semaphore;
    private final SdkEventBus eventBus;

    public BulkheadInterceptor(int maxConcurrent, SdkEventBus eventBus) {
        this.semaphore = new Semaphore(maxConcurrent);
        this.eventBus = eventBus;
    }

    @Override
    public void before(OperationContext ctx) {
        if (!semaphore.tryAcquire()) {
            if (eventBus != null) {
                eventBus.fire(SdkEvent.BULKHEAD_REJECTED, "Bulkhead full: " + ctx.action());
            }
            throw new com.authcses.sdk.exception.AuthCsesException(
                    "Bulkhead rejected: max concurrent requests exceeded");
        }
        ctx.setAttribute("_bulkhead_acquired", true);
    }

    @Override
    public void after(OperationContext ctx) {
        Boolean acquired = ctx.getAttribute("_bulkhead_acquired");
        if (acquired != null && acquired) {
            semaphore.release();
        }
    }
}
