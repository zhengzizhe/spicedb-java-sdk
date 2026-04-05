package com.authcses.sdk.event;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of TypedEventBus.
 * Thread-safe: ConcurrentHashMap for per-type listeners, CopyOnWriteArrayList for listener lists.
 */
public class DefaultTypedEventBus implements TypedEventBus {

    private final ConcurrentHashMap<Class<?>, List<TypedEventListener<?>>> listeners = new ConcurrentHashMap<>();
    private final List<TypedEventListener<SdkTypedEvent>> globalListeners = new CopyOnWriteArrayList<>();

    @Override
    public <E extends SdkTypedEvent> Registration subscribe(Class<E> eventType, TypedEventListener<E> listener) {
        var list = listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    @Override
    public Registration subscribeAll(TypedEventListener<SdkTypedEvent> listener) {
        globalListeners.add(listener);
        return () -> globalListeners.remove(listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void publish(SdkTypedEvent event) {
        // Notify type-specific listeners
        var list = listeners.get(event.getClass());
        if (list != null) {
            for (var listener : list) {
                try {
                    ((TypedEventListener<SdkTypedEvent>) listener).onEvent(event);
                } catch (Exception e) {
                    // Don't let one listener failure break others
                }
            }
        }
        // Notify global listeners
        for (var listener : globalListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ignored) {}
        }
    }
}
