package com.authx.sdk.spi;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A single Watch-stream listener dispatch that the SDK failed to enqueue
 * because the listener executor's queue was full (under
 * {@link QueueFullPolicy#DROP}).
 *
 * <p>Raised via the handler registered with
 * {@code SdkComponents.Builder#watchListenerDropHandler}. The event is
 * emitted from the gRPC callback thread — handlers MUST NOT block; do audit
 * writes / alerting asynchronously.
 *
 * @param zedToken       SpiceDB revision token for the transaction that
 *                       produced the dropped change, or {@code null} if the
 *                       response did not carry a token.
 * @param resourceType   resource type of the dropped relationship change
 * @param resourceId     resource id of the dropped relationship change
 * @param queueDepth     size of the listener executor queue at the moment of
 *                       drop (normally == queue capacity when the rejection
 *                       fires). {@code -1} if the executor does not expose a
 *                       queue (user-supplied non-TPE executor).
 * @param timestamp      wall-clock instant when the drop was detected
 */
public record DroppedListenerEvent(
        @Nullable String zedToken,
        String resourceType,
        String resourceId,
        int queueDepth,
        Instant timestamp
) {}
