package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

/**
 * Result of a SpiceDB schema write.
 *
 * @param zedToken revision token produced by the write, when the server
 *                 provides one
 */
public record SchemaWriteResult(@Nullable String zedToken) {

    /**
     * Creates a consistency level that reads at least as fresh as this schema
     * write.
     *
     * @return {@link Consistency#atLeast(String)} when a ZedToken is present,
     * otherwise {@link Consistency#full()}
     */
    public Consistency asConsistency() {
        return zedToken != null ? Consistency.atLeast(zedToken) : Consistency.full();
    }
}
