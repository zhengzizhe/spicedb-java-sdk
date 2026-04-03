package com.authcses.sdk.model;

import java.util.*;
import java.util.stream.Stream;

/**
 * Result of checking multiple permissions for multiple subjects.
 * Matrix[userId][permission] → CheckResult.
 */
public class PermissionMatrix {

    private final Map<String, PermissionSet> matrix;

    public PermissionMatrix(Map<String, PermissionSet> matrix) {
        this.matrix = matrix;
    }

    public PermissionSet get(String userId) {
        return matrix.get(userId);
    }

    public Map<String, PermissionSet> toMap() {
        return Collections.unmodifiableMap(matrix);
    }

    public Stream<Map.Entry<String, PermissionSet>> stream() {
        return matrix.entrySet().stream();
    }

    public List<String> whoCanAll(String... permissions) {
        return matrix.entrySet().stream()
                .filter(e -> Arrays.stream(permissions).allMatch(p -> e.getValue().can(p)))
                .map(Map.Entry::getKey)
                .toList();
    }

    public List<String> whoCanAny(String... permissions) {
        return matrix.entrySet().stream()
                .filter(e -> Arrays.stream(permissions).anyMatch(p -> e.getValue().can(p)))
                .map(Map.Entry::getKey)
                .toList();
    }
}
