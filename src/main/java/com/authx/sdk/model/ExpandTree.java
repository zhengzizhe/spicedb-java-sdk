package com.authx.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * Expanded permission tree from SpiceDB ExpandPermissionTree.
 * Shows how a permission is computed through the relation graph.
 */
public record ExpandTree(
        String operation,        // "union" | "intersection" | "exclusion" | "leaf"
        String resourceType,
        String resourceId,
        String relation,
        List<ExpandTree> children,
        List<String> subjects    // leaf nodes: subject references
) {
    /**
     * All leaf subject references in the tree.
     */
    public List<String> leaves() {
        if ("leaf".equals(operation) && subjects != null) return subjects;
        if (children == null) return List.of();
        return children.stream().flatMap(c -> c.leaves().stream()).toList();
    }

    /**
     * Maximum depth of the tree.
     */
    public int depth() {
        if (children == null || children.isEmpty()) return 1;
        return 1 + children.stream().mapToInt(ExpandTree::depth).max().orElse(0);
    }

    /**
     * Check if a subject exists anywhere in the tree.
     */
    public boolean contains(String subjectRef) {
        if (subjects != null && subjects.contains(subjectRef)) return true;
        if (children == null) return false;
        return children.stream().anyMatch(c -> c.contains(subjectRef));
    }
}
