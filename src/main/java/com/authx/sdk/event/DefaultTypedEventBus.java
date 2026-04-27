package com.authx.sdk.event;

import com.authx.sdk.trace.LogCtx;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Default implementation of TypedEventBus.
 * Thread-safe: ConcurrentHashMap for per-type listeners, CopyOnWriteArrayList for listener lists.
 *
 * <p>Supports optional async publishing via an {@link Executor}. By default, events are
 * published synchronously on the caller's thread. Pass an executor to enable async dispatch.
 */
public class DefaultTypedEventBus implements TypedEventBus {

    private static final System.Logger LOG = System.getLogger(DefaultTypedEventBus.class.getName());

    private final ConcurrentHashMap<Class<?>, List<TypedEventListener<?>>> listeners = new ConcurrentHashMap<>();
    private final List<TypedEventListener<SdkTypedEvent>> globalListeners = new CopyOnWriteArrayList<>();
    private final Executor publishExecutor;

    public DefaultTypedEventBus() {
        this.publishExecutor = Runnable::run; // synchronous by default
    }

    /** Create with an async executor for non-blocking event dispatch. */
    public DefaultTypedEventBus(Executor publishExecutor) {
        this.publishExecutor = publishExecutor != null ? publishExecutor : Runnable::run;
    }

    @Override
    public <E extends SdkTypedEvent> Registration subscribe(Class<E> eventType, TypedEventListener<E> listener) {
        java.util.List<com.authx.sdk.event.TypedEventListener<?>> list = listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
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
        publishExecutor.execute(() -> {
            // Notify type-specific listeners
            java.util.List<com.authx.sdk.event.TypedEventListener<?>> list = listeners.get(event.getClass());
            if (list != null) {
                for (com.authx.sdk.event.TypedEventListener<?> listener : list) {
                    try {
                        ((TypedEventListener<SdkTypedEvent>) listener).onEvent(event);
                    } catch (Exception e) {
                        LOG.log(System.Logger.Level.WARNING, LogCtx.fmt(
                                "Event listener error for {0}: {1}",
                                event.getClass().getSimpleName(), e.getMessage()));
                    }
                }
            }
            // Notify global listeners
            for (com.authx.sdk.event.TypedEventListener<com.authx.sdk.event.SdkTypedEvent> listener : globalListeners) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.WARNING, LogCtx.fmt(
                            "Global event listener error for {0}: {1}",
                            event.getClass().getSimpleName(), e.getMessage()));
                }
            }
        });
    }
}
