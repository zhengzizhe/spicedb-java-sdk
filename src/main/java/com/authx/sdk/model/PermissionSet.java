package com.authx.sdk.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import java.util.stream.Collectors;

/**
 * Result of checking multiple permissions for a single subject, keyed by permission name.
 */
public class PermissionSet {

    private final Map<String, CheckResult> results;

    /**
     * Creates a permission set from a map of permission name to check result.
     *
     * @param results map of permission name to {@link CheckResult}
     */
    public PermissionSet(Map<String, CheckResult> results) {
        this.results = results;
    }

    /**
     * Returns {@code true} if the subject has the specified permission.
     *
     * @param permission the permission name to test
     * @return whether the subject has the permission
     */
    public boolean can(String permission) {
        CheckResult r = results.get(permission);
        return r != null && r.hasPermission();
    }

    /**
     * Returns a map of permission name to boolean indicating whether the subject has each permission.
     *
     * @return map of permission name to granted status
     */
    public Map<String, Boolean> toMap() {
        return results.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().hasPermission()));
    }

    /**
     * Returns the set of permission names that the subject has.
     *
     * @return set of allowed permission names
     */
    public Set<String> allowed() {
        return results.entrySet().stream()
                .filter(e -> e.getValue().hasPermission())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * Returns the set of permission names that the subject does not have.
     *
     * @return set of denied permission names
     */
    public Set<String> denied() {
        return results.entrySet().stream()
                .filter(e -> !e.getValue().hasPermission())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * Returns all check results as a list.
     *
     * @return immutable list of {@link CheckResult} values
     */
    public List<CheckResult> toList() {
        return List.copyOf(results.values());
    }

    /**
     * Returns a stream over all permission-name-to-result entries.
     *
     * @return stream of map entries
     */
    public Stream<Map.Entry<String, CheckResult>> stream() {
        return results.entrySet().stream();
    }
}
