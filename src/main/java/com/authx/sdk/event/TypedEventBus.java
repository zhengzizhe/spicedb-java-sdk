package com.authx.sdk.event;

/**
 * Type-safe event bus. Listeners subscribe to specific event types.
 */
public interface TypedEventBus {

    /** Subscribe to a specific event type. Returns a registration that can be used to unsubscribe. */
    <E extends SdkTypedEvent> Registration subscribe(Class<E> eventType, TypedEventListener<E> listener);

    /** Subscribe to ALL events. */
    Registration subscribeAll(TypedEventListener<SdkTypedEvent> listener);

    /** Publish an event to all matching subscribers. */
    void publish(SdkTypedEvent event);

    interface Registration extends AutoCloseable {
        void unsubscribe();
        @Override default void close() { unsubscribe(); }
    }
}
