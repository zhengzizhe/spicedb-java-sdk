package com.authx.sdk.model;

/**
 * Result of a batch (mixed grant + revoke) operation.
 */
public record BatchResult(String zedToken) {
    public Consistency asConsistency() {
        return zedToken != null ? Consistency.atLeast(zedToken) : Consistency.full();
    }
}
