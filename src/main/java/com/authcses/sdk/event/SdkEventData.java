package com.authcses.sdk.event;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable event data carrier. Each event type carries relevant context.
 *
 * <pre>
 * client.eventBus().on(SdkEvent.CIRCUIT_OPENED, event -> {
 *     log.warn("Circuit opened at {}: {}", event.timestamp(), event.message());
 * });
 * </pre>
 */
public record SdkEventData(
        SdkEvent event,
        Instant timestamp,
        String message,
        Map<String, Object> attributes,
        Throwable cause
) {
    public SdkEventData(SdkEvent event, String message) {
        this(event, Instant.now(), message, Map.of(), null);
    }

    public SdkEventData(SdkEvent event, String message, Throwable cause) {
        this(event, Instant.now(), message, Map.of(), cause);
    }

    public SdkEventData(SdkEvent event, String message, Map<String, Object> attributes) {
        this(event, Instant.now(), message, attributes, null);
    }

    @SuppressWarnings("unchecked")
    public <T> T attribute(String key) {
        return (T) attributes.get(key);
    }
}
