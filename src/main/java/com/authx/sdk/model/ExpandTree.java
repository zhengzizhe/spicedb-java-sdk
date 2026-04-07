package com.authx.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * Expanded permission tree from SpiceDB's {@code ExpandPermissionTree} API, showing
 * how a permission is computed through the relation graph.
 *
 * @param operation    the set operation at this node ({@code "union"}, {@code "intersection"}, {@code "exclusion"}, or {@code "leaf"})
 * @param resourceType the resource object type for this tree node
 * @param resourceId   the resource object id for this tree node
 * @param relation     the relation or permission name at this node
 * @param children     child subtrees (empty for leaf nodes)
 * @param subjects     subject reference strings at leaf nodes (empty for non-leaf nodes)
 */
public record ExpandTree(
        String operation,
        String resourceType,
        String resourceId,
        String relation,
        List<ExpandTree> children,
        List<String> subjects
) {
    /**
     * Collects all leaf subject references in the tree by recursively traversing children.
     *
     * @return list of subject reference strings from all leaf nodes
     */
    public List<String> leaves() {
        if ("leaf".equals(operation) && subjects != null) return subjects;
        if (children == null) return List.of();
        return children.stream().flatMap(c -> c.leaves().stream()).toList();
    }

    /**
     * Computes the maximum depth of this tree (1 for a leaf node).
     *
     * @return the maximum depth
     */
    public int depth() {
        if (children == null || children.isEmpty()) return 1;
        return 1 + children.stream().mapToInt(ExpandTree::depth).max().orElse(0);
    }

    /**
     * Checks whether the given subject reference exists anywhere in the tree.
     *
     * @param subjectRef the subject reference string to search for (e.g. {@code "user:alice"})
     * @return {@code true} if the subject is found in any leaf node
     */
    public boolean contains(String subjectRef) {
        if (subjects != null && subjects.contains(subjectRef)) return true;
        if (children == null) return false;
        return children.stream().anyMatch(c -> c.contains(subjectRef));
    }
}
