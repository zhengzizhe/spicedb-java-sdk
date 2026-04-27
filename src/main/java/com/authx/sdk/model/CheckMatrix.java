package com.authx.sdk.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Result of a permission-check matrix query — N resources × M permissions × K subjects.
 *
 * <p>Replaces the previous
 * {@code Map<String, Map<String, Map<String, Boolean>>>} return type which
 * was a usability footgun: three levels of nullable {@code Map.get()} calls,
 * no compile-time guarantee the caller passed the right key at each level,
 * and awful to print for debugging.
 *
 * <p>Backed by a flat internal representation keyed on
 * {@code (resourceId, permission, subjectId)} so lookups are O(1) and the
 * whole matrix can be iterated in insertion order.
 *
 * <pre>
 * CheckMatrix result = doc.select("doc-1", "doc-2")
 *         .check(Document.Perm.VIEW, Document.Perm.EDIT)
 *         .by("alice", "bob");
 *
 * // O(1) point lookup — returns false if the cell is missing, never null
 * boolean b = result.allowed("doc-1", "view", "alice");
 *
 * // Whole-axis queries
 * boolean everyoneCanViewDoc1 = result.allAllowed("doc-1", "view");
 * boolean aliceCanDoAnything   = result.anyAllowedForSubject("alice");
 *
 * // Flat iteration for logging / diffing
 * result.forEach((id, perm, sub, allowed) -&gt;
 *         log.info("{}:{} perm={} sub={} allowed={}", "document", id, perm, sub, allowed));
 * </pre>
 *
 * <p>This class is immutable; construct via
 * {@link Builder} — typically from inside the typed check action, so callers
 * never build one directly.
 */
public final class CheckMatrix {

    /** Flat row in the matrix. */
    public record Cell(String resourceId, String permission, String subjectId, boolean allowed) {
        public Cell {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(subjectId, "subjectId");
        }
    }

    /** Callback for {@link #forEach(Visitor)}. */
    @FunctionalInterface
    public interface Visitor {
        void visit(String resourceId, String permission, String subjectId, boolean allowed);
    }

    private final List<Cell> cells;
    // Pre-computed axis sets in insertion order so {@code resources() / permissions() / subjects()}
    // are O(1) and preserve whatever order the caller passed.
    private final Set<String> resources;
    private final Set<String> permissions;
    private final Set<String> subjects;
    // Flat key → boolean map for O(1) point lookup. Key is
    // "{resourceId}\0{permission}\0{subjectId}" — a 3-field composite with
    // NUL separators that cannot collide with real id chars.
    private final Map<String, Boolean> index;

    private CheckMatrix(List<Cell> cells, Set<String> resources,
                        Set<String> permissions, Set<String> subjects,
                        Map<String, Boolean> index) {
        this.cells = List.copyOf(cells);
        // unmodifiableSet + LinkedHashSet preserves insertion order.
        // Set.copyOf does NOT guarantee iteration order — using it here would
        // make the axis accessors non-deterministic and break test-and-log
        // call sites that rely on "you get back what you put in, in order".
        this.resources = Collections.unmodifiableSet(new LinkedHashSet<>(resources));
        this.permissions = Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
        this.subjects = Collections.unmodifiableSet(new LinkedHashSet<>(subjects));
        this.index = Map.copyOf(index);
    }

    /** Point lookup. Returns {@code false} if the cell is missing — never null. */
    public boolean allowed(String resourceId, String permission, String subjectId) {
        Boolean b = index.get(key(resourceId, permission, subjectId));
        return b != null && b;
    }

    /** True iff this (resourceId, permission, subjectId) row is present in the matrix. */
    public boolean contains(String resourceId, String permission, String subjectId) {
        return index.containsKey(key(resourceId, permission, subjectId));
    }

    /** True iff every subject is allowed {@code permission} on {@code resourceId}. */
    public boolean allAllowed(String resourceId, String permission) {
        return cells.stream()
                .filter(c -> c.resourceId.equals(resourceId) && c.permission.equals(permission))
                .allMatch(Cell::allowed);
    }

    /** True iff at least one subject is allowed {@code permission} on {@code resourceId}. */
    public boolean anyAllowed(String resourceId, String permission) {
        return cells.stream()
                .filter(c -> c.resourceId.equals(resourceId) && c.permission.equals(permission))
                .anyMatch(Cell::allowed);
    }

    /** True iff {@code subjectId} is allowed <b>every</b> permission on <b>every</b> resource. */
    public boolean allAllowedForSubject(String subjectId) {
        return cells.stream()
                .filter(c -> c.subjectId.equals(subjectId))
                .allMatch(Cell::allowed);
    }

    /** True iff {@code subjectId} is allowed <b>at least one</b> permission on <b>any</b> resource. */
    public boolean anyAllowedForSubject(String subjectId) {
        return cells.stream()
                .filter(c -> c.subjectId.equals(subjectId))
                .anyMatch(Cell::allowed);
    }

    /** True iff every cell in the matrix is allowed. */
    public boolean allAllowed() {
        return cells.stream().allMatch(Cell::allowed);
    }

    /** True iff at least one cell in the matrix is allowed. */
    public boolean anyAllowed() {
        return cells.stream().anyMatch(Cell::allowed);
    }

    /** True iff at least one cell in the matrix is denied. */
    public boolean anyDenied() {
        return cells.stream().anyMatch(c -> !c.allowed);
    }

    /** List of (permission → allowed) pairs for a specific resource/subject slice. */
    public Map<String, Boolean> forResourceAndSubject(String resourceId, String subjectId) {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        for (Cell c : cells) {
            if (c.resourceId.equals(resourceId) && c.subjectId.equals(subjectId)) {
                out.put(c.permission, c.allowed);
            }
        }
        return out;
    }

    /** Number of cells in the matrix. */
    public int size() { return cells.size(); }

    /** Unmodifiable view of every cell in insertion order. */
    public List<Cell> cells() { return cells; }

    /** Unmodifiable view of the distinct resource ids, in insertion order. */
    public Set<String> resources() { return resources; }

    /** Unmodifiable view of the distinct permission names, in insertion order. */
    public Set<String> permissions() { return permissions; }

    /** Unmodifiable view of the distinct subject ids, in insertion order. */
    public Set<String> subjects() { return subjects; }

    /** Flat visitor iteration. */
    public void forEach(Visitor visitor) {
        for (Cell c : cells) {
            visitor.visit(c.resourceId, c.permission, c.subjectId, c.allowed);
        }
    }

    @Override
    public String toString() {
        return "CheckMatrix{" + cells.size() + " cells, " + resources.size() + " resources, "
                + permissions.size() + " perms, " + subjects.size() + " subjects, "
                + "allAllowed=" + allAllowed() + '}';
    }

    private static String key(String r, String p, String s) { return r + '\0' + p + '\0' + s; }

    // ────────────────────────────────────────────────────────────────
    //  Builder
    // ────────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final ArrayList<Cell> cells = new ArrayList<>();
        private final Set<String> resources = new LinkedHashSet<>();
        private final Set<String> permissions = new LinkedHashSet<>();
        private final Set<String> subjects = new LinkedHashSet<>();
        private final Map<String, Boolean> index = new LinkedHashMap<>();

        public Builder add(String resourceId, String permission, String subjectId, boolean allowed) {
            cells.add(new Cell(resourceId, permission, subjectId, allowed));
            resources.add(resourceId);
            permissions.add(permission);
            subjects.add(subjectId);
            // Last-writer-wins on duplicate cells. In normal usage the caller
            // emits each (r, p, s) exactly once, so this is a defensive
            // guard against caller loops that accidentally repeat.
            index.put(key(resourceId, permission, subjectId), allowed);
            return this;
        }

        public CheckMatrix build() {
            return new CheckMatrix(cells, resources, permissions, subjects, index);
        }
    }
}
