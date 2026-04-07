package com.authx.sdk.event;

import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.enums.SdkAction;

import java.time.Duration;
import java.time.Instant;

/**
 * Type-safe event hierarchy. Each event type is a record with specific fields.
 * Uses sealed interface for exhaustive pattern matching.
 */
public sealed interface SdkTypedEvent permits
        SdkTypedEvent.ClientReady,
        SdkTypedEvent.ClientStopping,
        SdkTypedEvent.ClientStopped,
        SdkTypedEvent.CircuitOpened,
        SdkTypedEvent.CircuitClosed,
        SdkTypedEvent.CircuitHalfOpened,
        SdkTypedEvent.CacheHit,
        SdkTypedEvent.CacheMiss,
        SdkTypedEvent.CacheEviction,
        SdkTypedEvent.TransportCall,
        SdkTypedEvent.WatchConnected,
        SdkTypedEvent.WatchDisconnected,
        SdkTypedEvent.SchemaRefreshed,
        SdkTypedEvent.SchemaLoadFailed,
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

    // ---- Cache ----
    record CacheHit(Instant timestamp, CheckKey key) implements SdkTypedEvent {}
    record CacheMiss(Instant timestamp, CheckKey key) implements SdkTypedEvent {}
    record CacheEviction(Instant timestamp, CheckKey key, String cause) implements SdkTypedEvent {}

    // ---- Transport ----
    record TransportCall(Instant timestamp, SdkAction action, ResourceRef resource,
                         Duration latency, boolean success) implements SdkTypedEvent {}

    // ---- Watch ----
    record WatchConnected(Instant timestamp) implements SdkTypedEvent {}
    record WatchDisconnected(Instant timestamp, String reason) implements SdkTypedEvent {}

    // ---- Schema ----
    record SchemaRefreshed(Instant timestamp, int definitionCount) implements SdkTypedEvent {}
    record SchemaLoadFailed(Instant timestamp, Throwable error) implements SdkTypedEvent {}

    // ---- Rate Limiting ----
    record RateLimited(Instant timestamp, String action) implements SdkTypedEvent {}
    record BulkheadRejected(Instant timestamp, String action) implements SdkTypedEvent {}
}
