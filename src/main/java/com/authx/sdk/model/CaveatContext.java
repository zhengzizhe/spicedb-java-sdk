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
        values = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
    }

    public static CaveatContext of(Map<String, Object> values) {
        return new CaveatContext(values);
    }

    public Map<String, Object> asMap() {
        return values;
    }
}
