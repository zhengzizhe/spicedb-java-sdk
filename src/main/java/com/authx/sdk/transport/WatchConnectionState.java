package com.authx.sdk.transport;

/**
 * Observable lifecycle state of the SDK's Watch subscription to SpiceDB.
 *
 * <p>Exposed via {@link WatchCacheInvalidator#state()} so observability tools
 * (actuator endpoints, Prometheus gauges, log collectors) can distinguish
 * "never connected" from "stable" from "currently reconnecting" — previously
 * conflated under a single {@code watchReconnects} counter.
 *
 * <p>State transitions are monotonic per Watch <em>session</em> but the
 * invalidator may oscillate between {@link #CONNECTED} and {@link #RECONNECTING}
 * over its lifetime as SpiceDB rotates connections
 * (e.g. {@code --grpc-max-conn-age}).
 *
 * <pre>
 *    NOT_STARTED ──▶ CONNECTING ──▶ CONNECTED ──┐
 *                       ▲                       │
 *                       │                       ▼
 *                    RECONNECTING ◀─────── (disconnect)
 *                       │
 *                       └──▶ STOPPED (terminal — permanent error or close())
 * </pre>
 */
public enum WatchConnectionState {
    /** The Watch thread has not yet been {@code start()}-ed. */
    NOT_STARTED,

    /**
     * Initial connection attempt is in progress. The gRPC call has been
     * dispatched but HTTP/2 HEADERS have not yet been received, so the stream
     * is not yet usable.
     */
    CONNECTING,

    /**
     * Stream is established (HTTP/2 HEADERS received). Invalidation events
     * from SpiceDB will flow. Note this is detected via the
     * {@code ClientCall.Listener.onHeaders} callback, NOT the arrival of the
     * first data message — so a pure-read SpiceDB with no writes will still
     * correctly report {@code CONNECTED}.
     */
    CONNECTED,

    /**
     * Last session ended (normally or abnormally) and the Watch thread is
     * inside its backoff window waiting to reconnect. The SDK is still
     * functional — checks continue to hit the cache — but cross-instance
     * invalidations are not being received during this window.
     */
    RECONNECTING,

    /**
     * Terminal: {@code close()} was called, or a permanent error (UNIMPLEMENTED,
     * UNAUTHENTICATED, PERMISSION_DENIED) was encountered, or the retry budget
     * was exhausted. Cache invalidation now relies exclusively on TTL expiry.
     */
    STOPPED
}
