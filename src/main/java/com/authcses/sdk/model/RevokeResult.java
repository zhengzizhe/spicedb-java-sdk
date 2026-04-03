package com.authcses.sdk.model;

/**
 * Result of a revoke (delete relationships) operation.
 * {@code count} is the number of RelationshipUpdate entries in the request (DELETE is idempotent).
 */
public record RevokeResult(String zedToken, int count) {
    public Consistency asConsistency() {
        return zedToken != null ? Consistency.atLeast(zedToken) : Consistency.full();
    }
}
