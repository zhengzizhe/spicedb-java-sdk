package com.authcses.sdk.spi;

import java.util.Objects;

/**
 * Type-safe key for storing attributes in OperationContext.
 * Inspired by gRPC CallOptions.Key&lt;T&gt; and AWS ExecutionAttribute&lt;T&gt;.
 *
 * Usage:
 *   static final AttributeKey&lt;String&gt; TRACE_ID = AttributeKey.of("traceId", String.class);
 *   ctx.attr(TRACE_ID, "abc-123");
 *   String traceId = ctx.attr(TRACE_ID); // no cast needed
 */
public final class AttributeKey<T> {

    private final String name;
    private final Class<T> type;
    private final T defaultValue;

    private AttributeKey(String name, Class<T> type, T defaultValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.defaultValue = defaultValue;
    }

    public static <T> AttributeKey<T> of(String name, Class<T> type) {
        return new AttributeKey<>(name, type, null);
    }

    public static <T> AttributeKey<T> withDefault(String name, Class<T> type, T defaultValue) {
        return new AttributeKey<>(name, type, defaultValue);
    }

    public String name() { return name; }
    public Class<T> type() { return type; }
    public T defaultValue() { return defaultValue; }

    @Override public String toString() { return "AttributeKey[" + name + ", " + type.getSimpleName() + "]"; }
    @Override public boolean equals(Object o) { return this == o; } // identity equality (like Netty's AttributeKey)
    @Override public int hashCode() { return System.identityHashCode(this); }
}
