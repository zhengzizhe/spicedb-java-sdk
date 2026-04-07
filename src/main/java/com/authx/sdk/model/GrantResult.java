package com.authx.sdk.model;

/**
 * Result of a grant (write relationships) operation.
 * {@code count} is the number of RelationshipUpdate entries in the request (TOUCH is idempotent,
 * so count reflects requested writes, not net-new relationships).
 */
public record GrantResult(String zedToken, int count) {
    /**
     * Create a Consistency that reads at least as fresh as this write.
     * Use for write-after-read: {@code doc.check("view").withConsistency(grantResult.asConsistency()).by("alice")}
     */
    public Consistency asConsistency() {
        return zedToken != null ? Consistency.atLeast(zedToken) : Consistency.full();
    }
}
