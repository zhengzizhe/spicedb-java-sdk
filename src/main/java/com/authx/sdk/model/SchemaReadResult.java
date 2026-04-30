package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Raw schema text returned by SpiceDB.
 *
 * @param schemaText the SpiceDB schema DSL text
 * @param zedToken revision token attached to the read, when the server
 *                 provides one
 */
public record SchemaReadResult(String schemaText, @Nullable String zedToken) {

    public SchemaReadResult {
        Objects.requireNonNull(schemaText, "schemaText");
    }

    /**
     * Creates a consistency level that reads at least as fresh as this schema
     * read.
     *
     * @return {@link Consistency#atLeast(String)} when a ZedToken is present,
     * otherwise {@link Consistency#full()}
     */
    public Consistency asConsistency() {
        return zedToken != null ? Consistency.atLeast(zedToken) : Consistency.full();
    }
}
