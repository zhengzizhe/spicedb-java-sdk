package com.authcses.sdk.model;

import java.util.Objects;

/** Type-safe relation name. Distinguishes relations from permissions at compile time. */
public record Relation(String name) {
    public Relation { Objects.requireNonNull(name, "name"); }
    public static Relation of(String name) { return new Relation(name); }
}
