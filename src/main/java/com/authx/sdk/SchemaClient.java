package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SubjectType;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public read-only view of the loaded SpiceDB schema.
 * Exposed via {@link AuthxClient#schema()}.
 *
 * <pre>
 * SchemaClient schema = client.schema();
 * schema.resourceTypes();                            // ["document", "folder", "group"]
 * schema.relationsOf("document");                    // ["owner", "editor", "viewer", "folder", ...]
 * schema.permissionsOf("document");                  // ["view", "edit", "delete", ...]
 * schema.subjectTypesOf("document", "viewer");       // [user, group#member, user:*]
 * schema.hasResourceType("document");                // true / false
 * schema.isLoaded();                                 // false if SpiceDB lacks ReflectSchema
 * </pre>
 *
 * <p>When the SDK was built without a live SpiceDB connection (e.g.
 * {@link AuthxClient#inMemory()}) the underlying cache is {@code null}
 * and every getter returns an empty collection / {@code false} — so
 * callers never need a null check.
 */
public class SchemaClient {

    private final @Nullable SchemaCache cache;

    public SchemaClient(@Nullable SchemaCache cache) {
        this.cache = cache;
    }

    /** {@code true} iff at least one definition is loaded. */
    public boolean isLoaded() {
        return cache != null && cache.hasSchema();
    }

    public Set<String> resourceTypes() {
        return cache != null ? cache.getResourceTypes() : Set.of();
    }

    public boolean hasResourceType(String type) {
        return cache != null && cache.hasResourceType(type);
    }

    public Set<String> relationsOf(String resourceType) {
        return cache != null ? cache.getRelations(resourceType) : Set.of();
    }

    public Set<String> permissionsOf(String resourceType) {
        return cache != null ? cache.getPermissions(resourceType) : Set.of();
    }

    public List<SubjectType> subjectTypesOf(String resourceType, String relation) {
        return cache != null ? cache.getSubjectTypes(resourceType, relation) : List.of();
    }

    public Map<String, List<SubjectType>> allSubjectTypes(String resourceType) {
        return cache != null ? cache.getAllSubjectTypes(resourceType) : Map.of();
    }

    public Set<String> getCaveatNames() {
        return cache != null ? cache.getCaveatNames() : Set.of();
    }

    public SchemaCache.@Nullable CaveatDef getCaveat(String name) {
        return cache != null ? cache.getCaveat(name) : null;
    }
}
