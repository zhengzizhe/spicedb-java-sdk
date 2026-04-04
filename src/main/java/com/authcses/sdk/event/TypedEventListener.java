package com.authcses.sdk.event;

/**
 * Type-safe event listener. Subscribe to specific event types.
 *
 * Usage:
 *   bus.subscribe(SdkTypedEvent.CircuitOpened.class, event -> log.warn("Circuit opened for {}", event.resourceType()));
 */
@FunctionalInterface
public interface TypedEventListener<E extends SdkTypedEvent> {
    void onEvent(E event);
}
