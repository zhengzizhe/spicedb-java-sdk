package com.authcses.sdk;

import com.authcses.sdk.model.*;
import com.authcses.sdk.transport.SdkTransport;
import com.authcses.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authcses.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Cross-resource batch builder: atomic operations across multiple resources.
 *
 * <pre>
 * client.batch()
 *     .on(client.resource("document", "doc-1"))
 *         .grant("owner").to("carol")
 *     .on(client.resource("folder", "folder-1"))
 *         .grant("editor").to("dave")
 *         .revoke("viewer").from("eve")
 *     .execute();
 * </pre>
 */
public class CrossResourceBatchBuilder {

    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final List<RelationshipUpdate> updates = new ArrayList<>();

    CrossResourceBatchBuilder(SdkTransport transport, String defaultSubjectType) {
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
    }

    /** Target a specific resource for the next operations. */
    public ResourceScope on(ResourceHandle resource) {
        return new ResourceScope(this, resource.resourceType(), resource.resourceId());
    }

    /** Execute all accumulated operations in a single atomic gRPC call. */
    public BatchResult execute() {
        if (updates.isEmpty()) return new BatchResult(null);
        var r = transport.writeRelationships(updates);
        return new BatchResult(r.zedToken());
    }

    void addUpdate(RelationshipUpdate update) {
        updates.add(update);
    }

    public static class ResourceScope {
        private final CrossResourceBatchBuilder batch;
        private final String resourceType;
        private final String resourceId;

        ResourceScope(CrossResourceBatchBuilder batch, String resourceType, String resourceId) {
            this.batch = batch;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
        }

        public GrantScope grant(String... relations) {
            return new GrantScope(this, relations);
        }

        public RevokeScope revoke(String... relations) {
            return new RevokeScope(this, relations);
        }

        /** Switch to a different resource. */
        public ResourceScope on(ResourceHandle resource) {
            return batch.on(resource);
        }

        /** Execute all operations. */
        public BatchResult execute() {
            return batch.execute();
        }
    }

    public static class GrantScope {
        private final ResourceScope scope;
        private final String[] relations;

        GrantScope(ResourceScope scope, String[] relations) {
            this.scope = scope;
            this.relations = relations;
        }

        public ResourceScope to(String... userIds) {
            return to(Arrays.asList(userIds));
        }

        public ResourceScope to(Collection<String> userIds) {
            ResourceRef resource = ResourceRef.of(scope.resourceType, scope.resourceId);
            for (String rel : relations) {
                for (String uid : userIds) {
                    scope.batch.addUpdate(new RelationshipUpdate(
                            Operation.TOUCH,
                            resource,
                            Relation.of(rel),
                            SubjectRef.of(scope.batch.defaultSubjectType, uid, null)));
                }
            }
            return scope;
        }

        public ResourceScope toSubjects(String... subjectRefs) {
            ResourceRef resource = ResourceRef.of(scope.resourceType, scope.resourceId);
            for (String rel : relations) {
                for (String ref : subjectRefs) {
                    Ref parsed = Ref.parse(ref);
                    scope.batch.addUpdate(new RelationshipUpdate(
                            Operation.TOUCH,
                            resource,
                            Relation.of(rel),
                            SubjectRef.of(parsed.type(), parsed.id(), parsed.relation())));
                }
            }
            return scope;
        }
    }

    public static class RevokeScope {
        private final ResourceScope scope;
        private final String[] relations;

        RevokeScope(ResourceScope scope, String[] relations) {
            this.scope = scope;
            this.relations = relations;
        }

        public ResourceScope from(String... userIds) {
            return from(Arrays.asList(userIds));
        }

        public ResourceScope from(Collection<String> userIds) {
            ResourceRef resource = ResourceRef.of(scope.resourceType, scope.resourceId);
            for (String rel : relations) {
                for (String uid : userIds) {
                    scope.batch.addUpdate(new RelationshipUpdate(
                            Operation.DELETE,
                            resource,
                            Relation.of(rel),
                            SubjectRef.of(scope.batch.defaultSubjectType, uid, null)));
                }
            }
            return scope;
        }
    }
}
