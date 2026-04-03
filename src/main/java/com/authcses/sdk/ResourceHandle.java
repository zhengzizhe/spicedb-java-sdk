package com.authcses.sdk;

import com.authcses.sdk.model.*;
import com.authcses.sdk.transport.SdkTransport;
import com.authcses.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authcses.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A handle to a specific resource (e.g., document:doc-123).
 * All operations on this handle target this resource.
 * Thread-safe and stateless — safe to share across threads.
 */
public class ResourceHandle {

    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;

    ResourceHandle(String resourceType, String resourceId, SdkTransport transport, String defaultSubjectType) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
    }

    public String resourceType() { return resourceType; }
    public String resourceId() { return resourceId; }

    // ---- Grant ----

    public GrantAction grant(String... relations) {
        return new GrantAction(this, relations);
    }

    // ---- Revoke ----

    public RevokeAction revoke(String... relations) {
        return new RevokeAction(this, relations);
    }

    public RevokeAllAction revokeAll() {
        return new RevokeAllAction(this, null);
    }

    public RevokeAllAction revokeAll(String... relations) {
        return new RevokeAllAction(this, relations);
    }

    // ---- Check ----

    public CheckAction check(String permission) {
        return new CheckAction(this, new String[]{permission});
    }

    public CheckAllAction checkAll(String... permissions) {
        return new CheckAllAction(this, permissions);
    }

    // ---- Expand ----

    /**
     * Expand the permission tree — shows how a permission is computed through the relation graph.
     * Useful for debugging why a user does/doesn't have a permission.
     */
    public ExpandTree expand(String permission) {
        return transport.expand(resourceType, resourceId, permission, Consistency.full());
    }

    // ---- Who ----

    public WhoBuilder who() {
        return new WhoBuilder(this);
    }

    // ---- Relations ----

    public RelationQuery relations(String... relations) {
        return new RelationQuery(this, relations);
    }

    // ---- Batch ----

    public BatchBuilder batch() {
        return new BatchBuilder(this);
    }

    // ============================================================
    // Inner action classes
    // ============================================================

    public static class GrantAction {
        private final ResourceHandle handle;
        private final String[] relations;
        private java.time.Instant expiresAt;
        private String caveatName;
        private Map<String, Object> caveatContext;

        GrantAction(ResourceHandle handle, String[] relations) {
            this.handle = handle;
            this.relations = relations;
        }

        /** Set expiration time for the granted relationships. */
        public GrantAction expiresAt(java.time.Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /** Set expiration as a duration from now. */
        public GrantAction expiresIn(java.time.Duration duration) {
            this.expiresAt = java.time.Instant.now().plus(duration);
            return this;
        }

        /** Attach a caveat (conditional permission). */
        public GrantAction withCaveat(String caveatName, Map<String, Object> context) {
            this.caveatName = caveatName;
            this.caveatContext = context;
            return this;
        }

        public GrantResult to(String... userIds) {
            return to(Arrays.asList(userIds));
        }

        public GrantResult to(Collection<String> userIds) {
            return writeRelationships(userIds.stream()
                    .map(id -> new Ref(handle.defaultSubjectType, id, null))
                    .toList());
        }

        public GrantResult toSubjects(String... subjectRefs) {
            return toSubjects(Arrays.asList(subjectRefs));
        }

        public GrantResult toSubjects(Collection<String> subjectRefs) {
            return writeRelationships(subjectRefs.stream().map(Ref::parse).toList());
        }

        private GrantResult writeRelationships(List<Ref> subjects) {
            List<RelationshipUpdate> updates = new ArrayList<>();
            for (String rel : relations) {
                for (Ref sub : subjects) {
                    updates.add(new RelationshipUpdate(
                            Operation.TOUCH,
                            handle.resourceType, handle.resourceId, rel,
                            sub.type(), sub.id(), sub.relation(),
                            caveatName, caveatContext, expiresAt));
                }
            }
            return handle.transport.writeRelationships(updates);
        }
    }

    public static class RevokeAction {
        private final ResourceHandle handle;
        private final String[] relations;

        RevokeAction(ResourceHandle handle, String[] relations) {
            this.handle = handle;
            this.relations = relations;
        }

        public RevokeResult from(String... userIds) {
            return from(Arrays.asList(userIds));
        }

        public RevokeResult from(Collection<String> userIds) {
            return deleteRelationships(userIds.stream()
                    .map(id -> new Ref(handle.defaultSubjectType, id, null))
                    .toList());
        }

        public RevokeResult fromSubjects(String... subjectRefs) {
            return fromSubjects(Arrays.asList(subjectRefs));
        }

        public RevokeResult fromSubjects(Collection<String> subjectRefs) {
            return deleteRelationships(subjectRefs.stream().map(Ref::parse).toList());
        }

        private RevokeResult deleteRelationships(List<Ref> subjects) {
            List<RelationshipUpdate> updates = new ArrayList<>();
            for (String rel : relations) {
                for (Ref sub : subjects) {
                    updates.add(new RelationshipUpdate(
                            Operation.DELETE,
                            handle.resourceType, handle.resourceId, rel,
                            sub.type(), sub.id(), sub.relation()));
                }
            }
            return handle.transport.deleteRelationships(updates);
        }
    }

    public static class RevokeAllAction {
        private final ResourceHandle handle;
        private final String[] relations;

        RevokeAllAction(ResourceHandle handle, String[] relations) {
            this.handle = handle;
            this.relations = relations;
        }

        public RevokeResult from(String... userIds) {
            return from(Arrays.asList(userIds));
        }

        public RevokeResult from(Collection<String> userIds) {
            // Read all matching relationships with full consistency to ensure completeness
            List<Tuple> existing;
            if (relations == null || relations.length == 0) {
                existing = handle.transport.readRelationships(
                        handle.resourceType, handle.resourceId, null, Consistency.full());
            } else {
                existing = new ArrayList<>();
                for (String rel : relations) {
                    existing.addAll(handle.transport.readRelationships(
                            handle.resourceType, handle.resourceId, rel, Consistency.full()));
                }
            }

            Set<String> targetIds = new HashSet<>(userIds);
            List<RelationshipUpdate> updates = existing.stream()
                    .filter(t -> targetIds.contains(t.subjectId()))
                    .map(t -> new RelationshipUpdate(
                            Operation.DELETE,
                            t.resourceType(), t.resourceId(), t.relation(),
                            t.subjectType(), t.subjectId(), t.subjectRelation()))
                    .toList();

            if (updates.isEmpty()) {
                return new RevokeResult(null, 0);
            }
            return handle.transport.deleteRelationships(updates);
        }
    }

    public static class CheckAction {
        private final ResourceHandle handle;
        private final String[] permissions;
        private Consistency consistency = Consistency.minimizeLatency();
        private Map<String, Object> context;

        CheckAction(ResourceHandle handle, String[] permissions) {
            this.handle = handle;
            this.permissions = permissions;
        }

        public CheckAction withConsistency(Consistency consistency) {
            this.consistency = consistency;
            return this;
        }

        /** Caveat context for conditional permissions (e.g., IP range, time). */
        public CheckAction withContext(Map<String, Object> context) {
            this.context = context;
            return this;
        }

        public CheckResult by(String userId) {
            if (context != null) {
                return handle.transport.check(
                        handle.resourceType, handle.resourceId,
                        permissions[0], handle.defaultSubjectType, userId,
                        consistency, context);
            }
            return handle.transport.check(
                    handle.resourceType, handle.resourceId,
                    permissions[0], handle.defaultSubjectType, userId,
                    consistency);
        }

        /** Async version. */
        public java.util.concurrent.CompletableFuture<CheckResult> byAsync(String userId) {
            return java.util.concurrent.CompletableFuture.supplyAsync(() -> by(userId));
        }

        public BulkCheckResult byAll(String... userIds) {
            return byAll(Arrays.asList(userIds));
        }

        public BulkCheckResult byAll(Collection<String> userIds) {
            return handle.transport.checkBulk(
                    handle.resourceType, handle.resourceId,
                    permissions[0], List.copyOf(userIds), handle.defaultSubjectType,
                    consistency);
        }
    }

    public static class CheckAllAction {
        private final ResourceHandle handle;
        private final String[] permissions;
        private Consistency consistency = Consistency.minimizeLatency();

        CheckAllAction(ResourceHandle handle, String[] permissions) {
            this.handle = handle;
            this.permissions = permissions;
        }

        public CheckAllAction withConsistency(Consistency consistency) {
            this.consistency = consistency;
            return this;
        }

        public PermissionSet by(String userId) {
            Map<String, CheckResult> results = new LinkedHashMap<>();
            for (String perm : permissions) {
                results.put(perm, handle.transport.check(
                        handle.resourceType, handle.resourceId,
                        perm, handle.defaultSubjectType, userId,
                        consistency));
            }
            return new PermissionSet(results);
        }

        public PermissionMatrix byAll(String... userIds) {
            return byAll(Arrays.asList(userIds));
        }

        public PermissionMatrix byAll(Collection<String> userIds) {
            Map<String, PermissionSet> matrix = new LinkedHashMap<>();
            for (String uid : userIds) {
                matrix.put(uid, by(uid));
            }
            return new PermissionMatrix(matrix);
        }
    }

    public static class WhoBuilder {
        private final ResourceHandle handle;

        WhoBuilder(ResourceHandle handle) {
            this.handle = handle;
        }

        public SubjectQuery withPermission(String permission) {
            return new SubjectQuery(handle, permission, true);
        }

        public SubjectQuery withRelation(String relation) {
            return new SubjectQuery(handle, relation, false);
        }
    }

    public static class SubjectQuery {
        private final ResourceHandle handle;
        private final String permissionOrRelation;
        private final boolean isPermission;
        private Consistency consistency = Consistency.minimizeLatency();

        SubjectQuery(ResourceHandle handle, String permissionOrRelation, boolean isPermission) {
            this.handle = handle;
            this.permissionOrRelation = permissionOrRelation;
            this.isPermission = isPermission;
        }

        public SubjectQuery withConsistency(Consistency consistency) {
            this.consistency = consistency;
            return this;
        }

        public List<String> fetch() {
            if (isPermission) {
                return handle.transport.lookupSubjects(
                        handle.resourceType, handle.resourceId,
                        permissionOrRelation, handle.defaultSubjectType, consistency);
            } else {
                return handle.transport.readRelationships(
                                handle.resourceType, handle.resourceId,
                                permissionOrRelation, consistency).stream()
                        .map(Tuple::subjectId)
                        .toList();
            }
        }

        public Set<String> fetchSet() {
            return new HashSet<>(fetch());
        }

        public Optional<String> fetchFirst() {
            var list = fetch();
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        }

        public int fetchCount() {
            return fetch().size();
        }

        public boolean fetchExists() {
            return !fetch().isEmpty();
        }

        public java.util.concurrent.CompletableFuture<List<String>> fetchAsync() {
            return java.util.concurrent.CompletableFuture.supplyAsync(this::fetch);
        }
    }

    public static class RelationQuery {
        private final ResourceHandle handle;
        private final String[] relations;
        private Consistency consistency = Consistency.minimizeLatency();

        RelationQuery(ResourceHandle handle, String[] relations) {
            this.handle = handle;
            this.relations = relations;
        }

        public RelationQuery withConsistency(Consistency consistency) {
            this.consistency = consistency;
            return this;
        }

        public List<Tuple> fetch() {
            if (relations == null || relations.length == 0) {
                return handle.transport.readRelationships(
                        handle.resourceType, handle.resourceId, null, consistency);
            }
            List<Tuple> result = new ArrayList<>();
            for (String rel : relations) {
                result.addAll(handle.transport.readRelationships(
                        handle.resourceType, handle.resourceId, rel, consistency));
            }
            return result;
        }

        public Set<Tuple> fetchSet() {
            return new HashSet<>(fetch());
        }

        public int fetchCount() {
            return fetch().size();
        }

        public boolean fetchExists() {
            return !fetch().isEmpty();
        }

        public Optional<Tuple> fetchFirst() {
            var list = fetch();
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        }

        public List<String> fetchSubjectIds() {
            return fetch().stream().map(Tuple::subjectId).toList();
        }

        public Set<String> fetchSubjectIdSet() {
            return new HashSet<>(fetchSubjectIds());
        }

        public Map<String, List<Tuple>> fetchGroupByRelation() {
            return fetch().stream().collect(Collectors.groupingBy(Tuple::relation));
        }

        public Map<String, List<String>> fetchGroupByRelationSubjectIds() {
            return fetch().stream().collect(Collectors.groupingBy(
                    Tuple::relation,
                    Collectors.mapping(Tuple::subjectId, Collectors.toList())));
        }
    }

    public static class BatchBuilder {
        private final ResourceHandle handle;
        private final List<RelationshipUpdate> updates = new ArrayList<>();

        BatchBuilder(ResourceHandle handle) {
            this.handle = handle;
        }

        public BatchGrantAction grant(String... relations) {
            return new BatchGrantAction(this, relations);
        }

        public BatchRevokeAction revoke(String... relations) {
            return new BatchRevokeAction(this, relations);
        }

        void addUpdate(RelationshipUpdate update) {
            updates.add(update);
        }

        public BatchResult execute() {
            if (updates.isEmpty()) return new BatchResult(null);
            // Send all updates (TOUCH + DELETE) in a single writeRelationships call
            var r = handle.transport.writeRelationships(updates);
            return new BatchResult(r.zedToken());
        }
    }

    public static class BatchGrantAction {
        private final BatchBuilder batch;
        private final String[] relations;

        BatchGrantAction(BatchBuilder batch, String[] relations) {
            this.batch = batch;
            this.relations = relations;
        }

        public BatchBuilder to(String... userIds) {
            return to(Arrays.asList(userIds));
        }

        public BatchBuilder to(Collection<String> userIds) {
            for (String rel : relations) {
                for (String uid : userIds) {
                    batch.addUpdate(new RelationshipUpdate(
                            Operation.TOUCH,
                            batch.handle.resourceType, batch.handle.resourceId, rel,
                            batch.handle.defaultSubjectType, uid, null));
                }
            }
            return batch;
        }

        public BatchBuilder toSubjects(String... subjectRefs) {
            for (String rel : relations) {
                for (String ref : subjectRefs) {
                    Ref parsed = Ref.parse(ref);
                    batch.addUpdate(new RelationshipUpdate(
                            Operation.TOUCH,
                            batch.handle.resourceType, batch.handle.resourceId, rel,
                            parsed.type(), parsed.id(), parsed.relation()));
                }
            }
            return batch;
        }
    }

    public static class BatchRevokeAction {
        private final BatchBuilder batch;
        private final String[] relations;

        BatchRevokeAction(BatchBuilder batch, String[] relations) {
            this.batch = batch;
            this.relations = relations;
        }

        public BatchBuilder from(String... userIds) {
            return from(Arrays.asList(userIds));
        }

        public BatchBuilder from(Collection<String> userIds) {
            for (String rel : relations) {
                for (String uid : userIds) {
                    batch.addUpdate(new RelationshipUpdate(
                            Operation.DELETE,
                            batch.handle.resourceType, batch.handle.resourceId, rel,
                            batch.handle.defaultSubjectType, uid, null));
                }
            }
            return batch;
        }

        public BatchBuilder fromSubjects(String... subjectRefs) {
            for (String rel : relations) {
                for (String ref : subjectRefs) {
                    Ref parsed = Ref.parse(ref);
                    batch.addUpdate(new RelationshipUpdate(
                            Operation.DELETE,
                            batch.handle.resourceType, batch.handle.resourceId, rel,
                            parsed.type(), parsed.id(), parsed.relation()));
                }
            }
            return batch;
        }
    }
}
