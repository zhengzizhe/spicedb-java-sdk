package com.authx.sdk.transport;

import com.authx.sdk.exception.AuthxAuthException;
import com.authx.sdk.exception.AuthxConnectionException;
import com.authx.sdk.exception.AuthxException;
import com.authx.sdk.exception.AuthxInvalidArgumentException;
import com.authx.sdk.exception.AuthxPreconditionException;
import com.authx.sdk.exception.AuthxResourceExhaustedException;
import com.authx.sdk.exception.AuthxTimeoutException;
import com.authx.sdk.exception.AuthxUnimplementedException;
import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
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
import io.grpc.ClientInterceptors;
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

    private static final System.Logger LOG = System.getLogger(GrpcTransport.class.getName());
    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_STREAM_RESULTS = 10_000;

    private final ManagedChannel channel;
    private final Metadata authMetadata;
    private final long deadlineMs;
    private final PermissionsServiceGrpc.PermissionsServiceBlockingStub baseStub;

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
        var tracedChannel = ClientInterceptors.intercept(channel, new TraceParentInterceptor());
        this.baseStub = PermissionsServiceGrpc.newBlockingStub(tracedChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMetadata));
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

        return mapPermissionship(response.getPermissionship(), token);
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
                LOG.log(System.Logger.Level.WARNING,
                        "Bulk check item error (treating as NO_PERMISSION): {0}",
                        pair.getError().getMessage());
                cr = new CheckResult(Permissionship.NO_PERMISSION, bulkToken, Optional.empty());
            } else {
                cr = mapPermissionship(pair.getItem().getPermissionship(), bulkToken);
            }
            results.put(subjectIds.get(i), cr);
        }
        return new BulkCheckResult(results);
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items,
                                             Consistency consistency) {
        if (items.isEmpty()) return List.of();
        if (items.size() <= MAX_BATCH_SIZE) {
            return checkBulkBatch(items, consistency);
        }
        List<CheckResult> allResults = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i += MAX_BATCH_SIZE) {
            var batch = items.subList(i, Math.min(i + MAX_BATCH_SIZE, items.size()));
            allResults.addAll(checkBulkBatch(batch, consistency));
        }
        return allResults;
    }

    private List<CheckResult> checkBulkBatch(List<BulkCheckItem> items,
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

        List<CheckResult> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size() && i < response.getPairsCount(); i++) {
            var pair = response.getPairs(i);
            CheckResult cr;
            if (pair.hasError()) {
                LOG.log(System.Logger.Level.WARNING,
                        "Bulk check item error (treating as NO_PERMISSION): {0}",
                        pair.getError().getMessage());
                cr = new CheckResult(Permissionship.NO_PERMISSION, bulkToken, Optional.empty());
            } else {
                cr = mapPermissionship(pair.getItem().getPermissionship(), bulkToken);
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
        var requestBuilder = ReadRelationshipsRequest.newBuilder()
                .setRelationshipFilter(filterBuilder.build())
                .setConsistency(toGrpc(consistency));
        var request = requestBuilder.build();

        try {
            List<Tuple> tuples = new ArrayList<>();
            var iterator = stub().readRelationships(request);
            while (iterator.hasNext()) {
                if (tuples.size() >= MAX_STREAM_RESULTS) {
                    LOG.log(System.Logger.Level.WARNING,
                            "readRelationships truncated at {0} results for {1}:{2}",
                            MAX_STREAM_RESULTS, resource.type(), resource.id());
                    break;
                }
                var resp = iterator.next();
                var rel = resp.getRelationship();
                String subRel = rel.getSubject().getOptionalRelation();
                tuples.add(new Tuple(
                        rel.getResource().getObjectType(), rel.getResource().getObjectId(),
                        rel.getRelation(),
                        rel.getSubject().getObject().getObjectType(),
                        rel.getSubject().getObject().getObjectId(),
                        subRel.isEmpty() ? null : subRel));
            }
            return tuples;
        } catch (StatusRuntimeException e) {
            throw mapGrpcException(e);
        }
    }

    @Override
    public List<SubjectRef> lookupSubjects(LookupSubjectsRequest request) {
        var builder = com.authzed.api.v1.LookupSubjectsRequest.newBuilder()
                .setResource(objRef(request.resource()))
                .setPermission(request.permission().name())
                .setSubjectObjectType(request.subjectType())
                .setConsistency(toGrpc(request.consistency()));
        if (request.limit() > 0) builder.setOptionalConcreteLimit(request.limit());

        int limit = request.limit();
        try {
            List<SubjectRef> subjects = new ArrayList<>();
            var iterator = stub().lookupSubjects(builder.build());
            int effectiveLimit = limit > 0 ? Math.min(limit, MAX_STREAM_RESULTS) : MAX_STREAM_RESULTS;
            while (iterator.hasNext() && subjects.size() < effectiveLimit) {
                var resp = iterator.next();
                subjects.add(SubjectRef.of(request.subjectType(), resp.getSubject().getSubjectObjectId(), null));
            }
            return subjects;
        } catch (StatusRuntimeException e) {
            throw mapGrpcException(e);
        }
    }

    @Override
    public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        var builder = com.authzed.api.v1.LookupResourcesRequest.newBuilder()
                .setResourceObjectType(request.resourceType())
                .setPermission(request.permission().name())
                .setSubject(subRef(request.subject()))
                .setConsistency(toGrpc(request.consistency()));
        if (request.limit() > 0) builder.setOptionalLimit(request.limit());

        int rlimit = request.limit();
        try {
            List<ResourceRef> resources = new ArrayList<>();
            var iterator = stub().lookupResources(builder.build());
            int effectiveLimit = rlimit > 0 ? Math.min(rlimit, MAX_STREAM_RESULTS) : MAX_STREAM_RESULTS;
            while (iterator.hasNext() && resources.size() < effectiveLimit) {
                var resp = iterator.next();
                resources.add(ResourceRef.of(request.resourceType(), resp.getResourceObjectId()));
            }
            return resources;
        } catch (StatusRuntimeException e) {
            throw mapGrpcException(e);
        }
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
        // Channel lifecycle managed by AuthxClient.Builder — do not close here
    }

    // ---- Helpers ----

    private PermissionsServiceGrpc.PermissionsServiceBlockingStub stub() {
        return baseStub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
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
        var builder = Relationship.newBuilder()
                .setResource(objRef(u.resource()))
                .setRelation(u.relation().name())
                .setSubject(subRef(u.subject()));
        if (u.caveat() != null) {
            var caveatBuilder = com.authzed.api.v1.ContextualizedCaveat.newBuilder()
                    .setCaveatName(u.caveat().name());
            if (u.caveat().context() != null && !u.caveat().context().isEmpty()) {
                caveatBuilder.setContext(toStruct(u.caveat().context()));
            }
            builder.setOptionalCaveat(caveatBuilder.build());
        }
        return builder.build();
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

    private static CheckResult mapPermissionship(
            com.authzed.api.v1.CheckPermissionResponse.Permissionship p, String token) {
        return switch (p) {
            case PERMISSIONSHIP_HAS_PERMISSION ->
                    new CheckResult(Permissionship.HAS_PERMISSION, token, Optional.empty());
            case PERMISSIONSHIP_CONDITIONAL_PERMISSION ->
                    new CheckResult(Permissionship.CONDITIONAL_PERMISSION, token, Optional.empty());
            default ->
                    new CheckResult(Permissionship.NO_PERMISSION, token, Optional.empty());
        };
    }

    private RuntimeException mapGrpcException(StatusRuntimeException e) {
        return GrpcExceptionMapper.map(e);
    }

    private <T> T withErrorHandling(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            throw mapGrpcException(e);
        }
    }
}
