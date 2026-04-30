package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Result of comparing the current SpiceDB schema with another schema text.
 *
 * @param diffs schema differences reported by SpiceDB, mapped into SDK value
 *              types
 * @param zedToken revision token attached to the diff read, when the server
 *                 provides one
 */
public record SchemaDiffResult(List<SchemaDiff> diffs, @Nullable String zedToken) {

    public SchemaDiffResult {
        diffs = List.copyOf(Objects.requireNonNull(diffs, "diffs"));
    }

    public boolean hasDiffs() {
        return !diffs.isEmpty();
    }

    /**
     * Creates a consistency level that reads at least as fresh as this diff
     * read.
     *
     * @return {@link Consistency#atLeast(String)} when a ZedToken is present,
     * otherwise {@link Consistency#full()}
     */
    public Consistency asConsistency() {
        return zedToken != null ? Consistency.atLeast(zedToken) : Consistency.full();
    }
}
