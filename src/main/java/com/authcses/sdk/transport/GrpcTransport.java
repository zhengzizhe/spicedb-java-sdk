package com.authcses.sdk.transport;

import com.authcses.sdk.exception.AuthCsesConnectionException;
import com.authcses.sdk.exception.AuthCsesTimeoutException;
import com.authcses.sdk.model.*;
import com.authcses.sdk.model.enums.Permissionship;
import com.authzed.api.v1.AlgebraicSubjectSet;
import com.authzed.api.v1.DeleteRelationshipsRequest;
import com.authzed.api.v1.ExpandPermissionTreeRequest;
import com.authzed.api.v1.PermissionRelationshipTree;
import com.authzed.api.v1.SubjectFilter;
import com.authzed.api.v1.CheckBulkPermissionsRequest;
import com.authzed.api.v1.CheckBulkPermissionsRequestItem;
import com.authzed.api.v1.CheckPermissionRequest;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.PermissionsServiceGrpc;
import com.authzed.api.v1.ReadRelationshipsRequest;
import com.authzed.api.v1.Relationship;
import com.authzed.api.v1.RelationshipFilter;
import com.authzed.api.v1.SubjectReference;
import com.authzed.api.v1.WriteRelationshipsRequest;
import com.authzed.api.v1.ZedToken;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Data-plane transport: connects directly to SpiceDB via gRPC.
 * Uses the preshared key obtained from the platform via /sdk/connect.
 */
public class GrpcTransport implements SdkTransport {

    private final ManagedChannel channel;
    private final Metadata authMetadata;
    private final long deadlineMs;

    /**
     * Create with a pre-built channel (preferred — Builder configures keepalive, LB, TLS).
     */
    public GrpcTransport(ManagedChannel channel, String presharedKey, long deadlineMs) {
        this.channel = channel;
        this.deadlineMs = deadlineMs;
        this.authMetadata = new Metadata();
        authMetadata.put(
                Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer " + presharedKey);
    }

    @Override
    public CheckResult check(CheckRequest request) {
        var grpcRequestBuilder = CheckPermissionRequest.newBuilder()
                .setResource(objRef(request.resource()))
                .setPermission(request.permission().name())
                .setSubject(subRef(request.subject()))
                .setConsistency(toGrpc(request.consistency()));

        if (request.caveatContext() != null && !request.caveatContext().isEmpty()) {
            grpcRequestBuilder.setContext(toStruct(request.caveatContext()));
        }

        var response = withErrorHandling(() -> stub().checkPermission(grpcRequestBuilder.build()));
        String token = response.hasCheckedAt() ? response.getCheckedAt().getToken() : null;

        return switch (response.getPermissionship()) {
            case PERMISSIONSHIP_HAS_PERMISSION ->
                    new CheckResult(Permissionship.HAS_PERMISSION, token, Optional.empty());
            case PERMISSIONSHIP_CONDITIONAL_PERMISSION ->
                    new CheckResult(Permissionship.CONDITIONAL_PERMISSION, token, Optional.empty());
            default ->
                    new CheckResult(Permissionship.NO_PERMISSION, token, Optional.empty());
        };
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        var builder = CheckBulkPermissionsRequest.newBuilder()
                .setConsistency(toGrpc(request.consistency()));
        List<String> subjectIds = new ArrayList<>(subjects.size());
        for (SubjectRef sub : subjects) {
            subjectIds.add(sub.id());
            builder.addItems(CheckBulkPermissionsRequestItem.newBuilder()
                    .setResource(objRef(request.resource()))
                    .setPermission(request.permission().name())
                    .setSubject(subRef(sub)));
        }

        var response = withErrorHandling(() -> stub().checkBulkPermissions(builder.build()));

        Map<String, CheckResult> results = new LinkedHashMap<>();
        String bulkToken = response.hasCheckedAt() ? response.getCheckedAt().getToken() : null;
        for (int i = 0; i < subjectIds.size() && i < response.getPairsCount(); i++) {
            var pair = response.getPairs(i);
            CheckResult cr;
            if (pair.hasError()) {
                cr = new CheckResult(Permissionship.NO_PERMISSION, bulkToken, Optional.empty());
            } else {
                var item = pair.getItem();
                cr = switch (item.getPermissionship()) {
                    case PERMISSIONSHIP_HAS_PERMISSION ->
                            new CheckResult(Permissionship.HAS_PERMISSION, bulkToken, Optional.empty());
                    case PERMISSIONSHIP_CONDITIONAL_PERMISSION ->
                            new CheckResult(Permissionship.CONDITIONAL_PERMISSION, bulkToken, Optional.empty());
                    default ->
                            new CheckResult(Permissionship.NO_PERMISSION, bulkToken, Optional.empty());
                };
            }
            results.put(subjectIds.get(i), cr);
        }
        return new BulkCheckResult(results);
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items,
                                             Consistency consistency) {
        var builder = CheckBulkPermissionsRequest.newBuilder()
                .setConsistency(toGrpc(consistency));
        for (var item : items) {
            builder.addItems(CheckBulkPermissionsRequestItem.newBuilder()
                    .setResource(objRef(item.resource()))
                    .setPermission(item.permission().name())
                    .setSubject(subRef(item.subject())));
        }

        var response = withErrorHandling(() -> stub().checkBulkPermissions(builder.build()));
        String bulkToken = response.hasCheckedAt() ? response.getCheckedAt().getToken() : null;

        List<CheckResult> results = new java.util.ArrayList<>(items.size());
        for (int i = 0; i < items.size() && i < response.getPairsCount(); i++) {
            var pair = response.getPairs(i);
            CheckResult cr;
            if (pair.hasError()) {
                cr = new CheckResult(Permissionship.NO_PERMISSION, bulkToken, Optional.empty());
            } else {
                cr = switch (pair.getItem().getPermissionship()) {
                    case PERMISSIONSHIP_HAS_PERMISSION ->
                            new CheckResult(Permissionship.HAS_PERMISSION, bulkToken, Optional.empty());
                    case PERMISSIONSHIP_CONDITIONAL_PERMISSION ->
                            new CheckResult(Permissionship.CONDITIONAL_PERMISSION, bulkToken, Optional.empty());
                    default ->
                            new CheckResult(Permissionship.NO_PERMISSION, bulkToken, Optional.empty());
                };
            }
            results.add(cr);
        }
        return results;
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        var builder = WriteRelationshipsRequest.newBuilder();
        for (var u : updates) {
            builder.addUpdates(com.authzed.api.v1.RelationshipUpdate.newBuilder()
                    .setOperation(toGrpcOp(u.operation()))
                    .setRelationship(toGrpcRel(u)));
        }
        var response = withErrorHandling(() -> stub().writeRelationships(builder.build()));
        String token = response.hasWrittenAt() ? response.getWrittenAt().getToken() : null;
        return new GrantResult(token, updates.size());
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        var builder = WriteRelationshipsRequest.newBuilder();
        for (var u : updates) {
            builder.addUpdates(com.authzed.api.v1.RelationshipUpdate.newBuilder()
                    .setOperation(com.authzed.api.v1.RelationshipUpdate.Operation.OPERATION_DELETE)
                    .setRelationship(toGrpcRel(u)));
        }
        var response = withErrorHandling(() -> stub().writeRelationships(builder.build()));
        String token = response.hasWrittenAt() ? response.getWrittenAt().getToken() : null;
        return new RevokeResult(token, updates.size());
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency) {
        var filterBuilder = RelationshipFilter.newBuilder()
                .setResourceType(resource.type())
                .setOptionalResourceId(resource.id());
        if (relation != null) {
            filterBuilder.setOptionalRelation(relation.name());
        }
        var request = ReadRelationshipsRequest.newBuilder()
                .setRelationshipFilter(filterBuilder.build())
                .setConsistency(toGrpc(consistency))
                .build();

        List<Tuple> tuples = new ArrayList<>();
        var iterator = withErrorHandling(() -> stub().readRelationships(request));
        iterator.forEachRemaining(resp -> {
            var rel = resp.getRelationship();
            String subRel = rel.getSubject().getOptionalRelation();
            tuples.add(new Tuple(
                    rel.getResource().getObjectType(), rel.getResource().getObjectId(),
                    rel.getRelation(),
                    rel.getSubject().getObject().getObjectType(),
                    rel.getSubject().getObject().getObjectId(),
                    subRel.isEmpty() ? null : subRel));
        });
        return tuples;
    }

    @Override
    public List<String> lookupSubjects(LookupSubjectsRequest request, Consistency consistency) {
        var builder = com.authzed.api.v1.LookupSubjectsRequest.newBuilder()
                .setResource(objRef(request.resource()))
                .setPermission(request.permission().name())
                .setSubjectObjectType(request.subjectType())
                .setConsistency(toGrpc(consistency));
        if (request.limit() > 0) builder.setOptionalConcreteLimit(request.limit());

        List<String> subjects = new ArrayList<>();
        var iterator = withErrorHandling(() -> stub().lookupSubjects(builder.build()));
        iterator.forEachRemaining(resp ->
                subjects.add(resp.getSubject().getSubjectObjectId()));
        return subjects;
    }

    @Override
    public List<String> lookupResources(LookupResourcesRequest request, Consistency consistency) {
        var builder = com.authzed.api.v1.LookupResourcesRequest.newBuilder()
                .setResourceObjectType(request.resourceType())
                .setPermission(request.permission().name())
                .setSubject(subRef(request.subject()))
                .setConsistency(toGrpc(consistency));
        if (request.limit() > 0) builder.setOptionalLimit(request.limit());

        List<String> resources = new ArrayList<>();
        var iterator = withErrorHandling(() -> stub().lookupResources(builder.build()));
        iterator.forEachRemaining(resp ->
                resources.add(resp.getResourceObjectId()));
        return resources;
    }

    @Override
    public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        var filterBuilder = RelationshipFilter.newBuilder()
                .setResourceType(resource.type())
                .setOptionalResourceId(resource.id());
        if (optionalRelation != null) {
            filterBuilder.setOptionalRelation(optionalRelation.name());
        }

        var subFilter = SubjectFilter.newBuilder()
                .setSubjectType(subject.type())
                .setOptionalSubjectId(subject.id());
        filterBuilder.setOptionalSubjectFilter(subFilter);

        var request = DeleteRelationshipsRequest.newBuilder()
                .setRelationshipFilter(filterBuilder.build())
                .build();

        var response = withErrorHandling(() -> stub().deleteRelationships(request));
        String token = response.hasDeletedAt() ? response.getDeletedAt().getToken() : null;
        return new RevokeResult(token, 0);
    }

    @Override
    public ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency) {
        var request = ExpandPermissionTreeRequest.newBuilder()
                .setResource(objRef(resource))
                .setPermission(permission.name())
                .setConsistency(toGrpc(consistency))
                .build();

        var response = withErrorHandling(() -> stub().expandPermissionTree(request));
        return mapTree(response.getTreeRoot());
    }

    private static ExpandTree mapTree(PermissionRelationshipTree node) {
        String resType = node.hasExpandedObject() ? node.getExpandedObject().getObjectType() : null;
        String resId   = node.hasExpandedObject() ? node.getExpandedObject().getObjectId()   : null;
        String rel     = node.getExpandedRelation().isEmpty() ? null : node.getExpandedRelation();

        return switch (node.getTreeTypeCase()) {
            case INTERMEDIATE -> {
                AlgebraicSubjectSet intermediate = node.getIntermediate();
                String operation = switch (intermediate.getOperation()) {
                    case OPERATION_UNION        -> "union";
                    case OPERATION_INTERSECTION -> "intersection";
                    case OPERATION_EXCLUSION    -> "exclusion";
                    default                     -> "union";
                };
                List<ExpandTree> children = intermediate.getChildrenList().stream()
                        .map(GrpcTransport::mapTree)
                        .toList();
                yield new ExpandTree(operation, resType, resId, rel, children, List.of());
            }
            case LEAF -> {
                List<String> subjects = node.getLeaf().getSubjectsList().stream()
                        .map(s -> {
                            String ref = s.getObject().getObjectType() + ":" + s.getObject().getObjectId();
                            String optRel = s.getOptionalRelation();
                            return optRel.isEmpty() ? ref : ref + "#" + optRel;
                        })
                        .toList();
                yield new ExpandTree("leaf", resType, resId, rel, List.of(), subjects);
            }
            default -> new ExpandTree("leaf", resType, resId, rel, List.of(), List.of());
        };
    }

    @Override
    public void close() {
        // Channel lifecycle managed by AuthCsesClient.Builder — do not close here
    }

    // ---- Helpers ----

    private static final Metadata.Key<String> TRACEPARENT_KEY =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    private PermissionsServiceGrpc.PermissionsServiceBlockingStub stub() {
        Metadata headers = new Metadata();
        headers.merge(authMetadata);

        // W3C traceparent: from OTel active span (if available) or fallback
        String traceparent = com.authcses.sdk.trace.TraceContext.traceparent();
        if (traceparent != null) {
            headers.put(TRACEPARENT_KEY, traceparent);
        }

        return PermissionsServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private static ObjectReference objRef(ResourceRef ref) {
        return ObjectReference.newBuilder().setObjectType(ref.type()).setObjectId(ref.id()).build();
    }

    private static SubjectReference subRef(SubjectRef ref) {
        var b = SubjectReference.newBuilder().setObject(
                ObjectReference.newBuilder().setObjectType(ref.type()).setObjectId(ref.id()).build());
        if (ref.relation() != null) b.setOptionalRelation(ref.relation());
        return b.build();
    }

    private static com.authzed.api.v1.RelationshipUpdate.Operation toGrpcOp(RelationshipUpdate.Operation op) {
        return switch (op) {
            case TOUCH -> com.authzed.api.v1.RelationshipUpdate.Operation.OPERATION_TOUCH;
            case DELETE -> com.authzed.api.v1.RelationshipUpdate.Operation.OPERATION_DELETE;
        };
    }

    private static Relationship toGrpcRel(RelationshipUpdate u) {
        return Relationship.newBuilder()
                .setResource(objRef(u.resource()))
                .setRelation(u.relation().name())
                .setSubject(subRef(u.subject()))
                .build();
    }

    private static com.authzed.api.v1.Consistency toGrpc(Consistency c) {
        return switch (c) {
            case Consistency.Full ignored ->
                    com.authzed.api.v1.Consistency.newBuilder().setFullyConsistent(true).build();
            case Consistency.AtLeast al ->
                    com.authzed.api.v1.Consistency.newBuilder()
                            .setAtLeastAsFresh(ZedToken.newBuilder().setToken(al.zedToken())).build();
            case Consistency.AtExactSnapshot aes ->
                    com.authzed.api.v1.Consistency.newBuilder()
                            .setAtExactSnapshot(ZedToken.newBuilder().setToken(aes.zedToken())).build();
            case Consistency.MinimizeLatency ignored ->
                    com.authzed.api.v1.Consistency.newBuilder().setMinimizeLatency(true).build();
        };
    }

    private static Struct toStruct(Map<String, Object> map) {
        var builder = Struct.newBuilder();
        for (var entry : map.entrySet()) {
            builder.putFields(entry.getKey(), toValue(entry.getValue()));
        }
        return builder.build();
    }

    private static Value toValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValueValue(0).build();
        } else if (obj instanceof String s) {
            return Value.newBuilder().setStringValue(s).build();
        } else if (obj instanceof Number n) {
            return Value.newBuilder().setNumberValue(n.doubleValue()).build();
        } else if (obj instanceof Boolean b) {
            return Value.newBuilder().setBoolValue(b).build();
        } else {
            // Fallback: convert to string
            return Value.newBuilder().setStringValue(obj.toString()).build();
        }
    }

    private <T> T withErrorHandling(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            throw switch (e.getStatus().getCode()) {
                case DEADLINE_EXCEEDED ->
                        new AuthCsesTimeoutException("SpiceDB request timed out", e);
                case UNAVAILABLE, CANCELLED ->
                        new AuthCsesConnectionException("SpiceDB unavailable", e);
                case UNAUTHENTICATED ->
                        new com.authcses.sdk.exception.AuthCsesAuthException("SpiceDB auth failed", e);
                default ->
                        new com.authcses.sdk.exception.AuthCsesException("SpiceDB error: " + e.getStatus(), e);
            };
        }
    }
}
