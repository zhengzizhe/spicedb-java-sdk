package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

/**
 * Result of a relationship write operation.
 *
 * <p>SpiceDB returns the revision token for a successful write, but it does not
 * report how many relationships were newly created or actually removed.
 * {@code submittedUpdateCount} is therefore the number of relationship updates
 * submitted by the SDK, not a net database-change count. Operations such as
 * {@code deleteByFilter} do not expose a submitted update list and therefore use
 * {@link #UNKNOWN_SUBMITTED_UPDATE_COUNT}.
 *
 * @param zedToken    the ZedToken from SpiceDB representing the write revision
 * @param submittedUpdateCount number of relationship updates submitted by the
 *                             SDK, or
 *                             {@link #UNKNOWN_SUBMITTED_UPDATE_COUNT} when
 *                             SpiceDB does not report a count
 */
public record WriteResult(@Nullable String zedToken, int submittedUpdateCount) {
    public static final int UNKNOWN_SUBMITTED_UPDATE_COUNT = -1;

    public WriteResult {
        if (submittedUpdateCount < UNKNOWN_SUBMITTED_UPDATE_COUNT) {
            throw new IllegalArgumentException("submittedUpdateCount must be >= -1");
        }
    }

    public static WriteResult unknownSubmittedUpdateCount(@Nullable String zedToken) {
        return new WriteResult(zedToken, UNKNOWN_SUBMITTED_UPDATE_COUNT);
    }

    /**
     * Returns whether {@link #submittedUpdateCount()} is known.
     *
     * @return {@code false} when {@link #submittedUpdateCount()} is
     * {@link #UNKNOWN_SUBMITTED_UPDATE_COUNT}
     */
    public boolean submittedUpdateCountKnown() {
        return submittedUpdateCount != UNKNOWN_SUBMITTED_UPDATE_COUNT;
    }

    /**
     * Compatibility predicate for call sites that use the older update-count
     * wording.
     *
     * @return {@link #submittedUpdateCountKnown()}
     */
    public boolean updateCountKnown() {
        return submittedUpdateCountKnown();
    }

    /**
     * Compatibility alias for call sites that use the older update-count
     * wording.
     *
     * @return {@link #submittedUpdateCount()}
     */
    public int updateCount() {
        return submittedUpdateCount;
    }

    /**
     * Compatibility alias for call sites that read old write result counts.
     *
     * @return {@link #submittedUpdateCount()}
     */
    public int count() {
        return submittedUpdateCount;
    }

    /**
     * Creates a consistency level that reads at least as fresh as this write.
     *
     * @return {@link Consistency#atLeast} using this result's ZedToken, or
     * {@link Consistency#full()} if the token is null
     */
    public Consistency asConsistency() {
        return zedToken != null ? Consistency.atLeast(zedToken) : Consistency.full();
    }
}
