package com.authx.sdk.model;

import java.util.*;

/**
 * Result of checking a single permission against multiple subjects.
 */
public class BulkCheckResult {

    private final Map<String, CheckResult> results;

    public BulkCheckResult(Map<String, CheckResult> results) {
        this.results = results;
    }

    public CheckResult get(String userId) {
        return results.get(userId);
    }

    public Map<String, CheckResult> asMap() {
        return Collections.unmodifiableMap(results);
    }

    public List<String> allowed() {
        return results.entrySet().stream()
                .filter(e -> e.getValue().hasPermission())
                .map(Map.Entry::getKey)
                .toList();
    }

    public Set<String> allowedSet() {
        return new HashSet<>(allowed());
    }

    public List<String> denied() {
        return results.entrySet().stream()
                .filter(e -> !e.getValue().hasPermission())
                .map(Map.Entry::getKey)
                .toList();
    }

    public boolean allAllowed() {
        return results.values().stream().allMatch(CheckResult::hasPermission);
    }

    public boolean anyAllowed() {
        return results.values().stream().anyMatch(CheckResult::hasPermission);
    }

    public int allowedCount() {
        return (int) results.values().stream().filter(CheckResult::hasPermission).count();
    }
}
