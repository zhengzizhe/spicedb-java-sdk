package com.authx.sdk.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable caveat context values for check-time caveat evaluation.
 *
 * <p>Generated caveat builders return this type after validating parameter
 * names and Java value types. The raw map APIs remain available for dynamic or
 * future SpiceDB caveat types.
 *
 * @param values context values keyed by SpiceDB caveat parameter name
 */
public record CaveatContext(Map<String, Object> values) {

    public CaveatContext {
        Objects.requireNonNull(values, "values");
        for (String key : values.keySet()) {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException(
                        "Caveat context keys must be non-null and non-empty");
            }
        }
        values = freezeMap(values, 0);
    }


    private static Map<String, Object> freezeMap(Map<?, ?> values, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Caveat context nesting exceeds 64 levels");
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!(key instanceof String text) || text.isEmpty()) {
                throw new IllegalArgumentException("Caveat context keys must be non-empty strings");
            }
            copy.put(text, freeze(value, depth + 1));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Caveat context nesting exceeds 64 levels");
        if (value instanceof Map<?, ?> map) return freezeMap(map, depth);
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> copy = new java.util.ArrayList<>(list.size());
            for (Object item : list) copy.add(freeze(item, depth + 1));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof byte[] bytes) return java.util.Base64.getEncoder().encodeToString(bytes);
        if (value instanceof java.util.concurrent.atomic.AtomicInteger number) return number.intValue();
        if (value instanceof java.util.concurrent.atomic.AtomicLong number) return number.longValue();
        return value;
    }

    public static CaveatContext of(Map<String, Object> values) {
        return new CaveatContext(values);
    }

    public Map<String, Object> asMap() {
        return values;
    }
}
