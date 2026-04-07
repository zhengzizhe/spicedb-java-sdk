package com.authx.sdk.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Result of checking multiple permissions for multiple subjects, forming a two-dimensional
 * matrix of {@code [subjectId][permission] -> CheckResult}.
 */
public class PermissionMatrix {

    private final Map<String, PermissionSet> matrix;

    /**
     * Creates a permission matrix from a map of subject id to permission set.
     *
     * @param matrix map of subject id to {@link PermissionSet}
     */
    public PermissionMatrix(Map<String, PermissionSet> matrix) {
        this.matrix = matrix;
    }

    /**
     * Returns the permission set for a specific subject.
     *
     * @param userId the subject id to look up
     * @return the {@link PermissionSet} for the subject, or {@code null} if not present
     */
    public PermissionSet get(String userId) {
        return matrix.get(userId);
    }

    /**
     * Returns an unmodifiable view of the full matrix.
     *
     * @return unmodifiable map of subject id to {@link PermissionSet}
     */
    public Map<String, PermissionSet> toMap() {
        return Collections.unmodifiableMap(matrix);
    }

    /**
     * Returns a stream over all subject-to-permission-set entries.
     *
     * @return stream of map entries
     */
    public Stream<Map.Entry<String, PermissionSet>> stream() {
        return matrix.entrySet().stream();
    }

    /**
     * Returns the list of subject ids that have <em>all</em> of the specified permissions.
     *
     * @param permissions the permission names to check
     * @return list of subject ids that satisfy every permission
     */
    public List<String> whoCanAll(String... permissions) {
        return matrix.entrySet().stream()
                .filter(e -> Arrays.stream(permissions).allMatch(p -> e.getValue().can(p)))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Returns the list of subject ids that have <em>any</em> of the specified permissions.
     *
     * @param permissions the permission names to check
     * @return list of subject ids that satisfy at least one permission
     */
    public List<String> whoCanAny(String... permissions) {
        return matrix.entrySet().stream()
                .filter(e -> Arrays.stream(permissions).anyMatch(p -> e.getValue().can(p)))
                .map(Map.Entry::getKey)
                .toList();
    }
}
