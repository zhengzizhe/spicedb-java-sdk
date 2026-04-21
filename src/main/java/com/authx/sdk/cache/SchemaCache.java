package com.authx.sdk.cache;

import com.authx.sdk.model.SubjectType;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Metadata-only schema cache. Holds:
 * <ul>
 *   <li>resource definitions — relations + permissions + per-relation subject types</li>
 *   <li>caveat definitions — name + params + expression + comment</li>
 * </ul>
 *
 * <p><b>Deliberately excludes</b> any permission / check-decision caching
 * (ADR 2026-04-18 — SpiceDB server-side dispatch cache handles that).
 *
 * <p>Thread-safe via {@link AtomicReference} swaps. Readers never block.
 */
public class SchemaCache {

    /** Per-type bundle of relations, permissions, and relation→subject-types. */
    public record DefinitionCache(
            Set<String> relations,
            Set<String> permissions,
            Map<String, List<SubjectType>> relationSubjectTypes) {
        public DefinitionCache {
            relations = relations != null ? Set.copyOf(relations) : Set.of();
            permissions = permissions != null ? Set.copyOf(permissions) : Set.of();
            relationSubjectTypes = relationSubjectTypes != null
                    ? Map.copyOf(relationSubjectTypes)
                    : Map.of();
        }
    }

    /** Caveat definition reflected from the schema. */
    public record CaveatDef(
            String name,
            Map<String, String> parameters,
            String expression,
            String comment) {
        public CaveatDef {
            Objects.requireNonNull(name, "name");
            parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
            expression = expression != null ? expression : "";
            comment = comment != null ? comment : "";
        }
    }

    private final AtomicReference<Map<String, DefinitionCache>> defs =
            new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, CaveatDef>> caveats =
            new AtomicReference<>(Map.of());

    /** Replace all definitions atomically. */
    public void updateFromMap(Map<String, DefinitionCache> definitions) {
        defs.set(definitions != null ? Map.copyOf(definitions) : Map.of());
    }

    /** Replace all caveats atomically. */
    public void updateCaveats(Map<String, CaveatDef> in) {
        caveats.set(in != null ? Map.copyOf(in) : Map.of());
    }

    /** {@code true} iff at least one definition is loaded. */
    public boolean hasSchema() { return !defs.get().isEmpty(); }

    public Set<String> getResourceTypes() { return defs.get().keySet(); }

    public boolean hasResourceType(String type) { return defs.get().containsKey(type); }

    public Set<String> getRelations(String type) {
        var d = defs.get().get(type);
        return d != null ? d.relations() : Set.of();
    }

    public Set<String> getPermissions(String type) {
        var d = defs.get().get(type);
        return d != null ? d.permissions() : Set.of();
    }

    public List<SubjectType> getSubjectTypes(String type, String relation) {
        var d = defs.get().get(type);
        if (d == null) return List.of();
        var sts = d.relationSubjectTypes().get(relation);
        return sts != null ? sts : List.of();
    }

    public Map<String, List<SubjectType>> getAllSubjectTypes(String type) {
        var d = defs.get().get(type);
        return d != null ? d.relationSubjectTypes() : Map.of();
    }

    public Set<String> getCaveatNames() { return caveats.get().keySet(); }

    public @Nullable CaveatDef getCaveat(String name) { return caveats.get().get(name); }
}
