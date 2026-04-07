package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;

import java.util.Set;

/**
 * Schema information loaded from SpiceDB ReflectSchema API.
 *
 * <pre>
 * var schema = client.schema();
 * schema.resourceTypes();           // ["document", "folder", "group"]
 * schema.relationsOf("document");   // ["owner", "editor", "viewer", "parent"]
 * schema.permissionsOf("document"); // ["view", "edit", "delete", "comment", "share"]
 * schema.hasResourceType("doc");    // false
 * </pre>
 */
public class SchemaClient {

    private final SchemaCache schemaCache;

    SchemaClient(SchemaCache schemaCache) {
        this.schemaCache = schemaCache;
    }

    /** All known resource types. */
    public Set<String> resourceTypes() {
        // SchemaCache stores them internally — expose via a method
        return schemaCache != null && schemaCache.hasSchema()
                ? schemaCache.getResourceTypes()
                : Set.of();
    }

    /** Relations defined on a resource type. */
    public Set<String> relationsOf(String resourceType) {
        return schemaCache != null ? schemaCache.getRelations(resourceType) : Set.of();
    }

    /** Permissions defined on a resource type. */
    public Set<String> permissionsOf(String resourceType) {
        return schemaCache != null ? schemaCache.getPermissions(resourceType) : Set.of();
    }

    /** Check if a resource type exists in the schema. */
    public boolean hasResourceType(String resourceType) {
        return schemaCache != null && schemaCache.hasResourceType(resourceType);
    }

    /** Check if schema has been loaded. */
    public boolean isLoaded() {
        return schemaCache != null && schemaCache.hasSchema();
    }
}
