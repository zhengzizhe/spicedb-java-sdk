package com.authcses.sdk.model;

import java.util.Map;
import java.util.Objects;

/** Caveat reference for conditional permissions. Replaces (String caveatName, Map&lt;String, Object&gt; caveatContext). */
public record CaveatRef(String name, Map<String, Object> context) {
    public CaveatRef { Objects.requireNonNull(name, "name"); }
}
