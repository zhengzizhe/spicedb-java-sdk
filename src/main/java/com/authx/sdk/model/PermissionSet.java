package com.authx.sdk.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import java.util.stream.Collectors;

/**
 * Result of checking multiple permissions for a single subject.
 */
public class PermissionSet {

    private final Map<String, CheckResult> results;

    public PermissionSet(Map<String, CheckResult> results) {
        this.results = results;
    }

    public boolean can(String permission) {
        var r = results.get(permission);
        return r != null && r.hasPermission();
    }

    public Map<String, Boolean> toMap() {
        return results.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().hasPermission()));
    }

    public Set<String> allowed() {
        return results.entrySet().stream()
                .filter(e -> e.getValue().hasPermission())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public Set<String> denied() {
        return results.entrySet().stream()
                .filter(e -> !e.getValue().hasPermission())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public List<CheckResult> toList() {
        return List.copyOf(results.values());
    }

    public Stream<Map.Entry<String, CheckResult>> stream() {
        return results.entrySet().stream();
    }
}
