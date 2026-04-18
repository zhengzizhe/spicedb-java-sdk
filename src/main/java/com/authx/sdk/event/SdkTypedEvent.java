package com.authx.sdk.event;

import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.enums.SdkAction;

import java.time.Duration;
import java.time.Instant;

/**
 * Type-safe event hierarchy. Each event type is a record with specific fields.
 * Uses sealed interface for exhaustive pattern matching.
 *
 * <p>Note: cache-related events (CacheHit, CacheMiss, CacheEviction) and
 * Watch-related events (WatchConnected, WatchDisconnected, WatchStreamStale,
 * WatchCursorExpired) were removed with those subsystems in ADR 2026-04-18.
 */
public sealed interface SdkTypedEvent permits
        SdkTypedEvent.ClientReady,
        SdkTypedEvent.ClientStopping,
        SdkTypedEvent.ClientStopped,
        SdkTypedEvent.CircuitOpened,
        SdkTypedEvent.CircuitClosed,
        SdkTypedEvent.CircuitHalfOpened,
        SdkTypedEvent.TransportCall,
        SdkTypedEvent.TokenStoreUnavailable,
        SdkTypedEvent.TokenStoreRecovered,
        SdkTypedEvent.RateLimited,
        SdkTypedEvent.BulkheadRejected {

    Instant timestamp();

    // ---- Client lifecycle ----
    record ClientReady(Instant timestamp, Duration startupDuration) implements SdkTypedEvent {}
    record ClientStopping(Instant timestamp) implements SdkTypedEvent {}
    record ClientStopped(Instant timestamp) implements SdkTypedEvent {}

    // ---- Circuit breaker ----
    record CircuitOpened(Instant timestamp, String resourceType, Throwable lastError) implements SdkTypedEvent {}
    record CircuitClosed(Instant timestamp, String resourceType) implements SdkTypedEvent {}
    record CircuitHalfOpened(Instant timestamp, String resourceType) implements SdkTypedEvent {}

    // ---- Transport ----
    record TransportCall(Instant timestamp, SdkAction action, ResourceRef resource,
                         Duration latency, boolean success) implements SdkTypedEvent {}

    /**
     * The configured {@code DistributedTokenStore} (e.g. Redis) became
     * unavailable. SESSION consistency for cross-instance reads has degraded
     * to local-only — instance B cannot see instance A's writes through
     * SESSION. Subscribe to alert that "cross-instance SESSION is broken".
     *
     * @param reason short error message from the underlying store
     */
    record TokenStoreUnavailable(Instant timestamp, String reason) implements SdkTypedEvent {}

    /** The previously-unavailable {@code DistributedTokenStore} accepted an
     *  operation again — cross-instance SESSION consistency restored. */
    record TokenStoreRecovered(Instant timestamp) implements SdkTypedEvent {}

    // ---- Rate Limiting ----
    record RateLimited(Instant timestamp, String action) implements SdkTypedEvent {}
    record BulkheadRejected(Instant timestamp, String action) implements SdkTypedEvent {}
}
