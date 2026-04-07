package com.authx.sdk.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of checking a single permission against multiple subjects, keyed by subject id.
 */
public class BulkCheckResult {

    private final Map<String, CheckResult> results;

    /**
     * Creates a bulk check result from a map of subject id to individual check results.
     *
     * @param results map of subject id to {@link CheckResult}
     */
    public BulkCheckResult(Map<String, CheckResult> results) {
        this.results = results;
    }

    /**
     * Returns the check result for a specific subject.
     *
     * @param userId the subject id to look up
     * @return the {@link CheckResult} for the subject, or {@code null} if not present
     */
    public CheckResult get(String userId) {
        return results.get(userId);
    }

    /**
     * Returns an unmodifiable view of all results as a map.
     *
     * @return unmodifiable map of subject id to {@link CheckResult}
     */
    public Map<String, CheckResult> asMap() {
        return Collections.unmodifiableMap(results);
    }

    /**
     * Returns the list of subject ids that have the requested permission.
     *
     * @return list of allowed subject ids
     */
    public List<String> allowed() {
        return results.entrySet().stream()
                .filter(e -> e.getValue().hasPermission())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Returns the set of subject ids that have the requested permission.
     *
     * @return set of allowed subject ids
     */
    public Set<String> allowedSet() {
        return new HashSet<>(allowed());
    }

    /**
     * Returns the list of subject ids that do not have the requested permission.
     *
     * @return list of denied subject ids
     */
    public List<String> denied() {
        return results.entrySet().stream()
                .filter(e -> !e.getValue().hasPermission())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Returns {@code true} if every subject in the result has the requested permission.
     *
     * @return whether all subjects are allowed
     */
    public boolean allAllowed() {
        return results.values().stream().allMatch(CheckResult::hasPermission);
    }

    /**
     * Returns {@code true} if at least one subject in the result has the requested permission.
     *
     * @return whether any subject is allowed
     */
    public boolean anyAllowed() {
        return results.values().stream().anyMatch(CheckResult::hasPermission);
    }

    /**
     * Returns the number of subjects that have the requested permission.
     *
     * @return count of allowed subjects
     */
    public int allowedCount() {
        return (int) results.values().stream().filter(CheckResult::hasPermission).count();
    }
}
