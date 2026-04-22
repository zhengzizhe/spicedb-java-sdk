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

    /**
     * Validate that {@code subjectRef} is an allowed subject for
     * {@code resourceType.relation}.
     *
     * <p><b>Fail-open</b> when the schema is not loaded, or when the
     * {@code resourceType} / {@code relation} is not in the cache, or when
     * the relation has no declared subject types. Those cases are left to
     * other validators (e.g. {@link com.authx.sdk.builtin.ValidationInterceptor})
     * so the same condition is not double-rejected with a confusing error.
     *
     * <p><b>Fail-fast</b> with {@link com.authx.sdk.exception.InvalidRelationException}
     * when the subject reference does not parse as {@code type:id} / {@code type:id#relation}
     * / {@code type:*}, or when the parsed subject does not match any
     * declared {@link SubjectType} on the relation. The error message
     * lists every allowed shape so the caller can correct the call site
     * without reading the schema by hand.
     */
    public void validateSubject(String resourceType, String relation, String subjectRef) {
        var d = defs.get().get(resourceType);
        if (d == null) return;                                  // fail-open
        var allowed = d.relationSubjectTypes().get(relation);
        if (allowed == null || allowed.isEmpty()) return;       // fail-open

        int colon = subjectRef.indexOf(':');
        if (colon < 0) {
            throw new com.authx.sdk.exception.InvalidRelationException(
                    "Invalid subject reference \"" + subjectRef
                            + "\": expected \"type:id\" or \"type:id#relation\" form");
        }
        String type = subjectRef.substring(0, colon);
        String idPart = subjectRef.substring(colon + 1);
        String subRelation = "";
        int hash = idPart.indexOf('#');
        if (hash >= 0) {
            subRelation = idPart.substring(hash + 1);
            idPart = idPart.substring(0, hash);
        }
        boolean isWildcard = "*".equals(idPart);

        for (SubjectType st : allowed) {
            if (!st.type().equals(type)) continue;
            if (isWildcard) {
                if (st.wildcard()) return;
                continue;
            }
            if (st.wildcard()) continue;
            String declared = st.relation() == null ? "" : st.relation();
            if (declared.equals(subRelation)) return;
        }

        String shapes = allowed.stream()
                .map(SubjectType::toRef)
                .distinct()
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        throw new com.authx.sdk.exception.InvalidRelationException(
                resourceType + "." + relation + " does not accept subject \""
                        + subjectRef + "\". Allowed subject types: " + shapes
                        + ". Check your schema, or use a different relation.");
    }
}
