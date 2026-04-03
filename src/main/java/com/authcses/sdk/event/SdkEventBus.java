package com.authcses.sdk.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe event bus. Components fire events, users subscribe to them.
 *
 * <pre>
 * // Subscribe
 * eventBus.on(SdkEvent.CIRCUIT_OPENED, event -> alertService.send("Circuit opened!"));
 * eventBus.on(SdkEvent.REFRESH_FAILED, event -> log.warn("Refresh failed: {}", event.message()));
 *
 * // Subscribe to ALL events
 * eventBus.onAll(event -> log.debug("SDK event: {} — {}", event.event(), event.message()));
 *
 * // Fire (called internally by SDK components)
 * eventBus.fire(new SdkEventData(SdkEvent.CIRCUIT_OPENED, "5 consecutive failures"));
 * </pre>
 *
 * <p>Listener exceptions are caught and logged, never propagated to the firing component.
 * Listeners execute synchronously on the firing thread — keep them fast.
 */
public class SdkEventBus {

    private static final System.Logger LOG = System.getLogger(SdkEventBus.class.getName());

    private final Map<SdkEvent, List<SdkEventListener>> listeners = new ConcurrentHashMap<>();
    private final List<SdkEventListener> globalListeners = new CopyOnWriteArrayList<>();

    /**
     * Subscribe to a specific event type.
     */
    public SdkEventBus on(SdkEvent event, SdkEventListener listener) {
        listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(listener);
        return this;
    }

    /**
     * Subscribe to ALL event types.
     */
    public SdkEventBus onAll(SdkEventListener listener) {
        globalListeners.add(listener);
        return this;
    }

    /**
     * Unsubscribe a listener from a specific event.
     */
    public SdkEventBus off(SdkEvent event, SdkEventListener listener) {
        var list = listeners.get(event);
        if (list != null) list.remove(listener);
        return this;
    }

    /**
     * Unsubscribe a global listener.
     */
    public SdkEventBus offAll(SdkEventListener listener) {
        globalListeners.remove(listener);
        return this;
    }

    /**
     * Fire an event. Called by SDK internal components.
     * Listeners execute synchronously. Exceptions are caught and logged.
     */
    public void fire(SdkEventData eventData) {
        // Type-specific listeners
        var typeListeners = listeners.get(eventData.event());
        if (typeListeners != null) {
            for (var listener : typeListeners) {
                invokeQuietly(listener, eventData);
            }
        }

        // Global listeners
        for (var listener : globalListeners) {
            invokeQuietly(listener, eventData);
        }
    }

    /**
     * Convenience: fire with just event + message.
     */
    public void fire(SdkEvent event, String message) {
        fire(new SdkEventData(event, message));
    }

    /**
     * Convenience: fire with event + message + cause.
     */
    public void fire(SdkEvent event, String message, Throwable cause) {
        fire(new SdkEventData(event, message, cause));
    }

    /**
     * Convenience: fire with event + message + attributes.
     */
    public void fire(SdkEvent event, String message, Map<String, Object> attrs) {
        fire(new SdkEventData(event, message, attrs));
    }

    /**
     * Check if any listeners are registered for an event.
     */
    public boolean hasListeners(SdkEvent event) {
        var list = listeners.get(event);
        return (list != null && !list.isEmpty()) || !globalListeners.isEmpty();
    }

    private void invokeQuietly(SdkEventListener listener, SdkEventData event) {
        try {
            listener.onEvent(event);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Event listener threw exception on {0}: {1}", event.event(), e.getMessage());
        }
    }
}
