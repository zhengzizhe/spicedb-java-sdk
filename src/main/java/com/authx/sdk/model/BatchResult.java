package com.authx.sdk.model;

/**
 * Result of a batch operation that may contain both grant and revoke updates in a single atomic write.
 *
 * @param zedToken the ZedToken from SpiceDB representing the batch write's revision
 */
public record BatchResult(String zedToken) {
    /**
     * Creates a {@link Consistency} level that reads at least as fresh as this batch write,
     * enabling safe read-after-write patterns.
     *
     * @return {@link Consistency#atLeast} using this result's ZedToken, or {@link Consistency#full()} if the token is null
     */
    public Consistency asConsistency() {
        return zedToken != null ? Consistency.atLeast(zedToken) : Consistency.full();
    }
}
