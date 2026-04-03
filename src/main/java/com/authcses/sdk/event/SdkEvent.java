package com.authcses.sdk.event;

/**
 * All SDK event types. Every significant state change in the SDK fires one of these.
 */
public enum SdkEvent {

    // ---- Lifecycle ----
    /** SDK client started successfully. */
    CLIENT_STARTED,
    /** SDK client is shutting down. */
    CLIENT_STOPPING,
    /** SDK client has fully shut down. */
    CLIENT_STOPPED,
    /** SDK is ready (all subsystems initialized). */
    CLIENT_READY,

    // ---- Connection ----
    /** gRPC channel established to SpiceDB. */
    CONNECTED,
    /** gRPC channel connection failed. */
    CONNECT_FAILED,

    // ---- Circuit Breaker ----
    /** Circuit breaker opened (SpiceDB appears unhealthy). */
    CIRCUIT_OPENED,
    /** Circuit breaker moved to half-open (probing recovery). */
    CIRCUIT_HALF_OPENED,
    /** Circuit breaker closed (SpiceDB recovered). */
    CIRCUIT_CLOSED,

    // ---- Cache ----
    /** Cache entry evicted (TTL expired or LRU). */
    CACHE_EVICTION,
    /** Cache was fully cleared. */
    CACHE_CLEARED,

    // ---- Watch ----
    /** Watch stream connected to SpiceDB. */
    WATCH_CONNECTED,
    /** Watch stream disconnected. */
    WATCH_DISCONNECTED,
    /** Watch stream reconnected after disconnect. */
    WATCH_RECONNECTED,

    // ---- Schema ----
    /** Schema loaded or refreshed from platform. */
    SCHEMA_UPDATED,
    /** Schema load failed. */
    SCHEMA_LOAD_FAILED,

    // ---- Security ----
    /** Namespace status changed to SUSPENDED. */
    NAMESPACE_SUSPENDED,
    /** Namespace status changed back to ACTIVE. */
    NAMESPACE_ACTIVATED,

    // ---- Telemetry ----
    /** Telemetry upload failed. */
    TELEMETRY_FAILED,

    // ---- Rate Limiting ----
    /** Request rejected by client-side rate limiter. */
    RATE_LIMITED,
    /** Request rejected by client-side bulkhead (max concurrency). */
    BULKHEAD_REJECTED,
}
