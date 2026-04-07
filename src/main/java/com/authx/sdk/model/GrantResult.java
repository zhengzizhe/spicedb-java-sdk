package com.authx.sdk.model;

/**
 * Result of a grant (write relationships) operation.
 *
 * <p>The {@code count} reflects the number of {@code RelationshipUpdate} entries in the request.
 * Because TOUCH is idempotent, count represents requested writes, not net-new relationships.
 *
 * @param zedToken the ZedToken from SpiceDB representing the write's revision
 * @param count    the number of relationship updates that were requested
 */
public record GrantResult(String zedToken, int count) {
    /**
     * Creates a {@link Consistency} level that reads at least as fresh as this write,
     * enabling safe read-after-write patterns.
     *
     * @return {@link Consistency#atLeast} using this result's ZedToken, or {@link Consistency#full()} if the token is null
     */
    public Consistency asConsistency() {
        return zedToken != null ? Consistency.atLeast(zedToken) : Consistency.full();
    }
}
