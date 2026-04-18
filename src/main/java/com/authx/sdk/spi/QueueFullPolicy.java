package com.authx.sdk.spi;

/**
 * Policy for what the Watch listener executor does when its bounded dispatch
 * queue fills up (only applies to the SDK-owned default executor — when the
 * caller supplies their own via
 * {@code SdkComponents.Builder#watchListenerExecutor(...)}, that executor's
 * own rejection handler is in charge).
 */
public enum QueueFullPolicy {
    /**
     * Drop the incoming dispatch, increment
     * {@code WatchCacheInvalidator.droppedListenerEvents()}, and invoke the
     * registered {@link DroppedListenerEvent} handler (if any).
     *
     * <p>Default — matches pre-SR:C5 behavior. Appropriate when listener load
     * is best-effort (audit logs, metrics) and it is acceptable to lose some
     * events under a burst.
     */
    DROP,

    /**
     * Block the gRPC callback thread until the queue has space for the new
     * task. Because the watch thread stops calling {@code ClientCall.request(1)}
     * until it drains a message from the queue, blocking the gRPC callback
     * thread propagates HTTP/2 window exhaustion upstream to SpiceDB, which
     * stops sending more Watch events. No drops occur.
     *
     * <p>Trade-off: a slow listener slows the entire Watch stream (and,
     * transitively, cache invalidation for other resource types sharing this
     * invalidator). Use when event loss is unacceptable and you have
     * headroom on the SpiceDB side.
     */
    BLOCK_WITH_BACKPRESSURE
}
