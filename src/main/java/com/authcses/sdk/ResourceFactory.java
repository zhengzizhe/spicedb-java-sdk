package com.authcses.sdk;

import com.authcses.sdk.transport.SdkTransport;

/**
 * Pre-bound factory for a specific resource type.
 * Subclass with {@link PermissionResource} annotation for typed usage.
 *
 * <pre>
 * // Option 1: bind()
 * ResourceFactory doc = client.bind("document");
 *
 * // Option 2: annotated subclass
 * @PermissionResource("document")
 * public class DocPermission extends ResourceFactory {}
 * DocPermission doc = client.create(DocPermission.class);
 *
 * // Use
 * doc.resource("doc-123").check("view").by("alice");
 * </pre>
 *
 * Thread-safe — safe to store as a field and share across requests.
 */
public class ResourceFactory {

    private volatile String resourceType;
    private volatile SdkTransport transport;
    private volatile String defaultSubjectType;

    /** For reflective instantiation by {@link AuthCsesClient#create(Class)}. */
    protected ResourceFactory() {}

    /** For direct instantiation by {@link AuthCsesClient#bind(String)}. */
    ResourceFactory(String resourceType, SdkTransport transport, String defaultSubjectType) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
    }

    /** Called by {@link AuthCsesClient#create(Class)} after reflective construction. */
    void init(String resourceType, SdkTransport transport, String defaultSubjectType) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
    }

    /**
     * Get a handle to a specific resource of this type.
     *
     * <pre>
     * doc.resource("doc-123").check("view").by("alice");
     * doc.resource("doc-123").grant("editor").to("bob");
     * doc.resource("doc-123").batch()
     *     .revoke("owner").from("old")
     *     .grant("owner").to("new")
     *     .execute();
     * </pre>
     */
    public ResourceHandle resource(String id) {
        return new ResourceHandle(resourceType, id, transport, defaultSubjectType);
    }

    /**
     * Lookup: find all resources of this type that a user has a permission on.
     */
    public LookupQuery lookup() {
        return new LookupQuery(resourceType, transport, defaultSubjectType);
    }

    // ================================================================
    //  Convenience methods — simple scenarios, no chaining needed.
    //  For complex scenarios (caveat, expiresAt, batch), use resource(id).xxx()
    // ================================================================

    // ---- Check ----

    /** Check a single permission. Returns true if allowed. */
    public boolean check(String id, String permission, String userId) {
        return resource(id).check(permission).by(userId).hasPermission();
    }

    /** Check with explicit consistency level. */
    public boolean check(String id, String permission, String userId, com.authcses.sdk.model.Consistency consistency) {
        return resource(id).check(permission).withConsistency(consistency).by(userId).hasPermission();
    }

    /** Check with caveat context (e.g., IP range, time). */
    public boolean check(String id, String permission, String userId, java.util.Map<String, Object> caveatContext) {
        return resource(id).check(permission).withContext(caveatContext).by(userId).hasPermission();
    }

    /** Check returning full result (includes zedToken, conditional status). */
    public com.authcses.sdk.model.CheckResult checkResult(String id, String permission, String userId) {
        return resource(id).check(permission).by(userId);
    }

    /** Async check. */
    public java.util.concurrent.CompletableFuture<Boolean> checkAsync(String id, String permission, String userId) {
        return resource(id).check(permission).byAsync(userId)
                .thenApply(com.authcses.sdk.model.CheckResult::hasPermission);
    }

    // ---- CheckAll ----

    /** Check multiple permissions at once. Returns map of permission→boolean. */
    public java.util.Map<String, Boolean> checkAll(String id, String userId, String... permissions) {
        return resource(id).checkAll(permissions).by(userId).toMap();
    }

    /** Check multiple permissions, returning rich PermissionSet. */
    public com.authcses.sdk.model.PermissionSet checkAllResult(String id, String userId, String... permissions) {
        return resource(id).checkAll(permissions).by(userId);
    }

    /** Check multiple permissions (Collection overload). */
    public java.util.Map<String, Boolean> checkAll(String id, String userId, java.util.Collection<String> permissions) {
        return resource(id).checkAll(permissions.toArray(String[]::new)).by(userId).toMap();
    }

    // ---- Check Bulk (1 permission × N users) ----

    /** Check one permission against multiple users. Returns who is allowed. */
    public java.util.List<String> filterAllowed(String id, String permission, String... userIds) {
        return resource(id).check(permission).byAll(userIds).allowed();
    }

    /** Check one permission against multiple users (Collection overload). */
    public java.util.List<String> filterAllowed(String id, String permission, java.util.Collection<String> userIds) {
        return resource(id).check(permission).byAll(userIds).allowed();
    }

    // ---- Grant ----

    /** Grant relation to user(s). */
    public void grant(String id, String relation, String... userIds) {
        resource(id).grant(relation).to(userIds);
    }

    /** Grant relation to user(s) — Collection overload. */
    public void grant(String id, String relation, java.util.Collection<String> userIds) {
        resource(id).grant(relation).to(userIds);
    }

    /** Grant relation to subject refs (e.g., "department:eng#member", "group:admins#member", "user:*"). */
    public void grantToSubjects(String id, String relation, String... subjectRefs) {
        resource(id).grant(relation).toSubjects(subjectRefs);
    }

    /** Grant relation to subject refs — Collection overload. */
    public void grantToSubjects(String id, String relation, java.util.Collection<String> subjectRefs) {
        resource(id).grant(relation).toSubjects(subjectRefs);
    }

    // ---- Revoke ----

    /** Revoke relation from user(s). */
    public void revoke(String id, String relation, String... userIds) {
        resource(id).revoke(relation).from(userIds);
    }

    /** Revoke relation from user(s) — Collection overload. */
    public void revoke(String id, String relation, java.util.Collection<String> userIds) {
        resource(id).revoke(relation).from(userIds);
    }

    /** Revoke relation from subject refs (e.g., "department:eng#member", "user:*"). */
    public void revokeFromSubjects(String id, String relation, String... subjectRefs) {
        resource(id).revoke(relation).fromSubjects(subjectRefs);
    }

    /** Revoke relation from subject refs — Collection overload. */
    public void revokeFromSubjects(String id, String relation, java.util.Collection<String> subjectRefs) {
        resource(id).revoke(relation).fromSubjects(subjectRefs);
    }

    // ---- RevokeAll ----

    /** Remove all relations for user(s) on this resource. */
    public void revokeAll(String id, String... userIds) {
        resource(id).revokeAll().from(userIds);
    }

    /** Remove all relations for user(s) — Collection overload. */
    public void revokeAll(String id, java.util.Collection<String> userIds) {
        resource(id).revokeAll().from(userIds);
    }

    /** Remove specific relations for user(s). */
    public void revokeAll(String id, String[] relations, String... userIds) {
        resource(id).revokeAll(relations).from(userIds);
    }

    // ---- Subject queries (who has permission/relation on this resource?) ----

    /** Find all subjects with a permission on this resource. */
    public java.util.List<String> subjects(String id, String permission) {
        return resource(id).who().withPermission(permission).fetch();
    }

    /** Find subjects with limit. */
    public java.util.List<String> subjects(String id, String permission, int limit) {
        return resource(id).who().withPermission(permission).limit(limit).fetch();
    }

    /** Find all subjects as Set. */
    public java.util.Set<String> subjectSet(String id, String permission) {
        return resource(id).who().withPermission(permission).fetchSet();
    }

    /** Count subjects with a permission. */
    public int subjectCount(String id, String permission) {
        return resource(id).who().withPermission(permission).fetchCount();
    }

    /** Check if any subject has a permission. */
    public boolean hasSubjects(String id, String permission) {
        return resource(id).who().withPermission(permission).fetchExists();
    }

    // ---- Relation queries (who holds a relation on this resource?) ----

    /** Find all users with a specific relation. */
    public java.util.List<String> relatedUsers(String id, String relation) {
        return resource(id).who().withRelation(relation).fetch();
    }

    /** Find all users with a specific relation, as Set. */
    public java.util.Set<String> relatedUserSet(String id, String relation) {
        return resource(id).who().withRelation(relation).fetchSet();
    }

    /** Get all relations grouped by relation name → subject IDs. */
    public java.util.Map<String, java.util.List<String>> allRelations(String id) {
        return resource(id).relations().groupByRelation();
    }

    /** Count total relationships on this resource. */
    public int relationCount(String id) {
        return resource(id).relations().fetchCount();
    }

    /** Count relationships for a specific relation. */
    public int relationCount(String id, String relation) {
        return resource(id).relations(relation).fetchCount();
    }

    /** Check if any relationship exists on this resource. */
    public boolean hasRelations(String id) {
        return resource(id).relations().fetchExists();
    }

    // ---- Resource lookup (what resources can a user access?) ----

    /** Find all resources of this type that a user has a permission on. */
    public java.util.List<String> resources(String permission, String userId) {
        return lookup().withPermission(permission).by(userId).fetch();
    }

    /** Find resources with limit. */
    public java.util.List<String> resources(String permission, String userId, int limit) {
        return lookup().withPermission(permission).by(userId).limit(limit).fetch();
    }

    /** Find all resources as Set. */
    public java.util.Set<String> resourceSet(String permission, String userId) {
        return lookup().withPermission(permission).by(userId).fetchSet();
    }

    /** Count resources a user can access. */
    public int resourceCount(String permission, String userId) {
        return lookup().withPermission(permission).by(userId).fetchCount();
    }

    /** Check if a user has access to any resource of this type. */
    public boolean hasResources(String permission, String userId) {
        return lookup().withPermission(permission).by(userId).fetchExists();
    }

    public String resourceType() {
        return resourceType;
    }
}
