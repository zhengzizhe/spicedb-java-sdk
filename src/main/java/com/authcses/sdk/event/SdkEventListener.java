package com.authcses.sdk.event;

/**
 * Functional interface for event listeners.
 *
 * <pre>
 * SdkEventListener listener = event -> log.info("Event: {}", event);
 * </pre>
 */
@FunctionalInterface
public interface SdkEventListener {

    /**
     * Called when the subscribed event fires.
     * Implementations MUST be thread-safe and non-blocking.
     * Exceptions are caught and logged, never propagated.
     */
    void onEvent(SdkEventData event);
}
