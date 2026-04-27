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
import com.authx.sdk.trace.LogCtx;
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

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Data-plane transport: connects directly to SpiceDB via gRPC.
 * Uses the preshared key obtained from the platform via /sdk/connect.
 */
public class GrpcTransport implements SdkTransport {

    private static final System.Logger LOG = System.getLogger(GrpcTransport.class.getName());
    private static final int MAX_BATCH_SIZE = 500;

    /**
     * Shared scheduler used by {@link io.grpc.Context#withDeadline(io.grpc.Deadline,
     * ScheduledExecutorService)} to fire automatic cancellation when the
     * per-call effective deadline elapses. Single-threaded daemon pool — the
     * scheduler only fires late-timeout tasks, so one thread is plenty.
     * Shared JVM-wide (static) because the tasks it runs are trivial and
     * creating one per-instance would leak if clients are short-lived.
     */
    private static final ScheduledExecutorService DEADLINE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "authx-grpc-deadline");
                t.setDaemon(true);
                return t;
            });

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
        io.grpc.Channel tracedChannel = ClientInterceptors.intercept(channel, new TraceParentInterceptor());
        this.baseStub = PermissionsServiceGrpc.newBlockingStub(tracedChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMetadata));
    }

    @Override
    public CheckResult check(CheckRequest request) {
        com.authzed.api.v1.CheckPermissionRequest.Builder grpcRequestBuilder = CheckPermissionRequest.newBuilder()
                .setResource(objRef(request.resource()))
                .setPermission(request.permission().name())
                .setSubject(subRef(request.subject()))
                .setConsistency(toGrpc(request.consistency()));

        if (request.caveatContext() != null && !request.caveatContext().isEmpty()) {
            grpcRequestBuilder.setContext(toStruct(request.caveatContext()));
        }

        com.authzed.api.v1.CheckPermissionResponse response = withErrorHandling(() -> stub().checkPermission(grpcRequestBuilder.build()));
        String token = response.hasCheckedAt() ? response.getCheckedAt().getToken() : null;

        return mapPermissionship(response.getPermissionship(), token);
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        com.authzed.api.v1.CheckBulkPermissionsRequest.Builder builder = CheckBulkPermissionsRequest.newBuilder()
                .setConsistency(toGrpc(request.consistency()));
        List<String> subjectIds = new ArrayList<>(subjects.size());
        for (SubjectRef sub : subjects) {
            subjectIds.add(sub.id());
            builder.addItems(CheckBulkPermissionsRequestItem.newBuilder()
                    .setResource(objRef(request.resource()))
                    .setPermission(request.permission().name())
                    .setSubject(subRef(sub)));
        }

        com.authzed.api.v1.CheckBulkPermissionsResponse response = withErrorHandling(() -> stub().checkBulkPermissions(builder.build()));

        Map<String, CheckResult> results = new LinkedHashMap<>();
        String bulkToken = response.hasCheckedAt() ? response.getCheckedAt().getToken() : null;
        for (int i = 0; i < subjectIds.size() && i < response.getPairsCount(); i++) {
            com.authzed.api.v1.CheckBulkPermissionsPair pair = response.getPairs(i);
            CheckResult cr;
            if (pair.hasError()) {
                LOG.log(System.Logger.Level.DEBUG, LogCtx.fmt(
                        "Bulk check item error (treating as NO_PERMISSION): {0}",
                        pair.getError().getMessage()));
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
            java.util.List<com.authx.sdk.transport.SdkTransport.BulkCheckItem> batch = items.subList(i, Math.min(i + MAX_BATCH_SIZE, items.size()));
            allResults.addAll(checkBulkBatch(batch, consistency));
        }
        return allResults;
    }

    private List<CheckResult> checkBulkBatch(List<BulkCheckItem> items,
                                              Consistency consistency) {
        com.authzed.api.v1.CheckBulkPermissionsRequest.Builder builder = CheckBulkPermissionsRequest.newBuilder()
                .setConsistency(toGrpc(consistency));
        for (com.authx.sdk.transport.SdkTransport.BulkCheckItem item : items) {
            builder.addItems(CheckBulkPermissionsRequestItem.newBuilder()
                    .setResource(objRef(item.resource()))
                    .setPermission(item.permission().name())
                    .setSubject(subRef(item.subject())));
        }

        com.authzed.api.v1.CheckBulkPermissionsResponse response = withErrorHandling(() -> stub().checkBulkPermissions(builder.build()));
        String bulkToken = response.hasCheckedAt() ? response.getCheckedAt().getToken() : null;

        List<CheckResult> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size() && i < response.getPairsCount(); i++) {
            com.authzed.api.v1.CheckBulkPermissionsPair pair = response.getPairs(i);
            CheckResult cr;
            if (pair.hasError()) {
                LOG.log(System.Logger.Level.DEBUG, LogCtx.fmt(
                        "Bulk check item error (treating as NO_PERMISSION): {0}",
                        pair.getError().getMessage()));
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
        com.authzed.api.v1.WriteRelationshipsRequest.Builder builder = WriteRelationshipsRequest.newBuilder();
        for (com.authx.sdk.transport.SdkTransport.RelationshipUpdate u : updates) {
            builder.addUpdates(com.authzed.api.v1.RelationshipUpdate.newBuilder()
                    .setOperation(toGrpcOp(u.operation()))
                    .setRelationship(toGrpcRel(u)));
        }
        com.authzed.api.v1.WriteRelationshipsResponse response = withErrorHandling(() -> stub().writeRelationships(builder.build()));
        String token = response.hasWrittenAt() ? response.getWrittenAt().getToken() : null;
        return new GrantResult(token, updates.size());
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        com.authzed.api.v1.WriteRelationshipsRequest.Builder builder = WriteRelationshipsRequest.newBuilder();
        for (com.authx.sdk.transport.SdkTransport.RelationshipUpdate u : updates) {
            builder.addUpdates(com.authzed.api.v1.RelationshipUpdate.newBuilder()
                    .setOperation(com.authzed.api.v1.RelationshipUpdate.Operation.OPERATION_DELETE)
                    .setRelationship(toGrpcRel(u)));
        }
        com.authzed.api.v1.WriteRelationshipsResponse response = withErrorHandling(() -> stub().writeRelationships(builder.build()));
        String token = response.hasWrittenAt() ? response.getWrittenAt().getToken() : null;
        return new RevokeResult(token, updates.size());
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency) {
        com.authzed.api.v1.RelationshipFilter.Builder filterBuilder = RelationshipFilter.newBuilder()
                .setResourceType(resource.type())
                .setOptionalResourceId(resource.id());
        if (relation != null) {
            filterBuilder.setOptionalRelation(relation.name());
        }
        com.authzed.api.v1.ReadRelationshipsRequest.Builder requestBuilder = ReadRelationshipsRequest.newBuilder()
                .setRelationshipFilter(filterBuilder.build())
                .setConsistency(toGrpc(consistency));
        com.authzed.api.v1.ReadRelationshipsRequest request = requestBuilder.build();

        try (com.authx.sdk.transport.CloseableGrpcIterator<com.authzed.api.v1.ReadRelationshipsResponse> iterator = CloseableGrpcIterator.from(
                () -> stub().readRelationships(request), newCallContext())) {
            List<Tuple> tuples = new ArrayList<>();
            while (iterator.hasNext()) {
                com.authzed.api.v1.ReadRelationshipsResponse resp = iterator.next();
                com.authzed.api.v1.Relationship rel = resp.getRelationship();
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
        // Note: intentionally NOT calling setOptionalConcreteLimit here.
        // Older SpiceDB versions (pre-concrete-limit support) return
        // "UNIMPLEMENTED: concrete limit is not yet supported" when the
        // field is set, and detecting the server version at runtime is
        // impractical. The limit is applied Java-side via the stream
        // iterator cutoff below — a few extra rows on the wire before
        // the stream is cancelled is negligible vs the compatibility gain.
        com.authzed.api.v1.LookupSubjectsRequest.Builder builder = com.authzed.api.v1.LookupSubjectsRequest.newBuilder()
                .setResource(objRef(request.resource()))
                .setPermission(request.permission().name())
                .setSubjectObjectType(request.subjectType())
                .setConsistency(toGrpc(request.consistency()));

        int limit = request.limit();
        try (com.authx.sdk.transport.CloseableGrpcIterator<com.authzed.api.v1.LookupSubjectsResponse> iterator = CloseableGrpcIterator.from(
                () -> stub().lookupSubjects(builder.build()), newCallContext())) {
            List<SubjectRef> subjects = new ArrayList<>();
            while (iterator.hasNext() && (limit <= 0 || subjects.size() < limit)) {
                com.authzed.api.v1.LookupSubjectsResponse resp = iterator.next();
                subjects.add(SubjectRef.of(request.subjectType(), resp.getSubject().getSubjectObjectId(), null));
            }
            return subjects;
        } catch (StatusRuntimeException e) {
            throw mapGrpcException(e);
        }
    }

    @Override
    public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        com.authzed.api.v1.LookupResourcesRequest.Builder builder = com.authzed.api.v1.LookupResourcesRequest.newBuilder()
                .setResourceObjectType(request.resourceType())
                .setPermission(request.permission().name())
                .setSubject(subRef(request.subject()))
                .setConsistency(toGrpc(request.consistency()));
        if (request.limit() > 0) builder.setOptionalLimit(request.limit());

        int rlimit = request.limit();
        try (com.authx.sdk.transport.CloseableGrpcIterator<com.authzed.api.v1.LookupResourcesResponse> iterator = CloseableGrpcIterator.from(
                () -> stub().lookupResources(builder.build()), newCallContext())) {
            List<ResourceRef> resources = new ArrayList<>();
            while (iterator.hasNext() && (rlimit <= 0 || resources.size() < rlimit)) {
                com.authzed.api.v1.LookupResourcesResponse resp = iterator.next();
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
        com.authzed.api.v1.RelationshipFilter.Builder filterBuilder = RelationshipFilter.newBuilder()
                .setResourceType(resource.type())
                .setOptionalResourceId(resource.id());
        if (optionalRelation != null) {
            filterBuilder.setOptionalRelation(optionalRelation.name());
        }

        com.authzed.api.v1.SubjectFilter.Builder subFilter = SubjectFilter.newBuilder()
                .setSubjectType(subject.type())
                .setOptionalSubjectId(subject.id());
        filterBuilder.setOptionalSubjectFilter(subFilter);

        com.authzed.api.v1.DeleteRelationshipsRequest request = DeleteRelationshipsRequest.newBuilder()
                .setRelationshipFilter(filterBuilder.build())
                .build();

        com.authzed.api.v1.DeleteRelationshipsResponse response = withErrorHandling(() -> stub().deleteRelationships(request));
        String token = response.hasDeletedAt() ? response.getDeletedAt().getToken() : null;
        return new RevokeResult(token, 0);
    }

    @Override
    public ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency) {
        com.authzed.api.v1.ExpandPermissionTreeRequest request = ExpandPermissionTreeRequest.newBuilder()
                .setResource(objRef(resource))
                .setPermission(permission.name())
                .setConsistency(toGrpc(consistency))
                .build();

        com.authzed.api.v1.ExpandPermissionTreeResponse response = withErrorHandling(() -> stub().expandPermissionTree(request));
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
        com.authzed.api.v1.SubjectReference.Builder b = SubjectReference.newBuilder().setObject(
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
        com.authzed.api.v1.Relationship.Builder builder = Relationship.newBuilder()
                .setResource(objRef(u.resource()))
                .setRelation(u.relation().name())
                .setSubject(subRef(u.subject()));
        if (u.caveat() != null) {
            com.authzed.api.v1.ContextualizedCaveat.Builder caveatBuilder = com.authzed.api.v1.ContextualizedCaveat.newBuilder()
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
        com.google.protobuf.Struct.Builder builder = Struct.newBuilder();
        for (java.util.Map.Entry<java.lang.String,java.lang.Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException(
                        "Caveat context map keys must be non-null and non-empty");
            }
            try {
                builder.putFields(key, toValue(entry.getValue()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Failed to convert caveat context field '" + key + "': " + e.getMessage(), e);
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static Value toValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValueValue(0).build();
        } else if (obj instanceof String s) {
            return Value.newBuilder().setStringValue(s).build();
        } else if (obj instanceof Number n) {
            return Value.newBuilder().setNumberValue(n.doubleValue()).build();
        } else if (obj instanceof Boolean b) {
            return Value.newBuilder().setBoolValue(b).build();
        } else if (obj instanceof Map<?, ?> m) {
            return Value.newBuilder()
                    .setStructValue(toStruct((Map<String, Object>) m))
                    .build();
        } else if (obj instanceof List<?> list) {
            com.google.protobuf.ListValue.Builder listBuilder = ListValue.newBuilder();
            for (Object element : list) {
                listBuilder.addValues(toValue(element));
            }
            return Value.newBuilder().setListValue(listBuilder).build();
        } else {
            throw new IllegalArgumentException(
                    "Unsupported caveat context value type: " + obj.getClass().getName());
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
        io.grpc.Context.CancellableContext ctx = newCallContext();
        io.grpc.Context prev = ctx.attach();
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            throw mapGrpcException(e);
        } finally {
            ctx.detach(prev);
            // Cancel eagerly to release any Context listeners gRPC attached for
            // deadline / cancellation propagation. The underlying unary call has
            // already returned (or thrown) by now, so cancelling the context is
            // a no-op on the RPC itself — it just frees the listener slot.
            ctx.cancel(null);
        }
    }

    /**
     * Build the {@link io.grpc.Context.CancellableContext} used for ONE gRPC
     * call (unary or streaming). The context:
     *
     * <ul>
     *   <li>Inherits from {@link io.grpc.Context#current()} so upstream
     *       cancellation (e.g. from an HTTP handler thread whose context was
     *       cancelled by the servlet container) propagates transitively.</li>
     *   <li>Applies an effective deadline of
     *       {@code min(Context.current().getDeadline(), now + policyTimeout)}.
     *       If the caller already carried a tighter deadline (e.g. the HTTP
     *       request had a 100ms SLA) we respect it — the gRPC call will fail
     *       with {@code DEADLINE_EXCEEDED} at the upstream deadline, not the
     *       (looser) SDK policy timeout.</li>
     *   <li>Is cancellable, so {@link io.grpc.Context.CancellableContext#cancel}
     *       or any upstream cancellation propagates to the bound ClientCall.</li>
     * </ul>
     *
     * <p>Gives {@link GrpcTransport} the explicit Context handling the
     * per-call stub deadline alone does not provide (SR:C1).
     */
    io.grpc.Context.CancellableContext newCallContext() {
        io.grpc.Deadline upstream = io.grpc.Context.current().getDeadline();
        io.grpc.Deadline policy = io.grpc.Deadline.after(deadlineMs, TimeUnit.MILLISECONDS);
        io.grpc.Deadline effective =
                (upstream != null && upstream.isBefore(policy)) ? upstream : policy;
        return io.grpc.Context.current()
                .withDeadline(effective, DEADLINE_SCHEDULER)
                .withCancellation();
    }
}
