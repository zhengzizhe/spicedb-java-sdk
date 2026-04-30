package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SchemaDiff;
import com.authx.sdk.model.SchemaDiffResult;
import com.authx.sdk.model.SchemaReadResult;
import com.authx.sdk.model.SchemaWriteResult;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.GrpcExceptionMapper;
import com.authx.sdk.transport.SchemaLoader;
import com.authzed.api.v1.DiffSchemaRequest;
import com.authzed.api.v1.ReadSchemaRequest;
import com.authzed.api.v1.ReflectionCaveat;
import com.authzed.api.v1.ReflectionCaveatParameter;
import com.authzed.api.v1.ReflectionCaveatParameterTypeChange;
import com.authzed.api.v1.ReflectionDefinition;
import com.authzed.api.v1.ReflectionPermission;
import com.authzed.api.v1.ReflectionRelation;
import com.authzed.api.v1.ReflectionRelationSubjectTypeChange;
import com.authzed.api.v1.ReflectionSchemaDiff;
import com.authzed.api.v1.SchemaServiceGrpc;
import com.authzed.api.v1.WriteSchemaRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Public schema management API exposed via {@link AuthxClient#schema()}.
 *
 * <p>The metadata methods ({@link #resourceTypes()}, {@link #relationsOf(String)}
 * and friends) read from the SDK's local schema cache. Raw management methods
 * ({@link #readRaw()}, {@link #writeRaw(String)}, {@link #diffRaw(String)} and
 * {@link #refresh()}) require a live SpiceDB-backed client.
 */
public class SchemaClient {

    private final @Nullable SchemaCache cache;
    private final @Nullable ManagedChannel channel;
    private final @Nullable Metadata authMetadata;
    private final long deadlineMs;
    private final @Nullable SchemaLoader loader;

    public SchemaClient(@Nullable SchemaCache cache) {
        this(cache, null, null, 0, null);
    }

    SchemaClient(
            @Nullable SchemaCache cache,
            @Nullable ManagedChannel channel,
            @Nullable Metadata authMetadata,
            long deadlineMs,
            @Nullable SchemaLoader loader) {
        this.cache = cache;
        this.channel = channel;
        this.authMetadata = authMetadata;
        this.deadlineMs = deadlineMs;
        this.loader = loader;
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

    /**
     * Reads the raw SpiceDB schema from a live server.
     *
     * @return schema DSL text and the read revision token, when returned
     * @throws UnsupportedOperationException when this client was not built with
     *                                       a live SpiceDB connection
     */
    public SchemaReadResult readRaw() {
        try {
            com.authzed.api.v1.ReadSchemaResponse response = stub().readSchema(
                    ReadSchemaRequest.newBuilder().build());
            return new SchemaReadResult(
                    response.getSchemaText(),
                    response.hasReadAt() ? response.getReadAt().getToken() : null);
        } catch (StatusRuntimeException e) {
            throw GrpcExceptionMapper.map(e);
        }
    }

    /**
     * Writes raw SpiceDB schema text and refreshes the SDK's local schema
     * metadata cache when one is attached.
     *
     * @param schema schema DSL text; must not be blank
     * @return write revision token, when returned
     * @throws IllegalArgumentException when {@code schema} is blank
     * @throws UnsupportedOperationException when this client was not built with
     *                                       a live SpiceDB connection
     */
    public SchemaWriteResult writeRaw(String schema) {
        Objects.requireNonNull(schema, "schema");
        if (schema.isBlank()) {
            throw new IllegalArgumentException("schema must not be blank");
        }

        try {
            com.authzed.api.v1.WriteSchemaResponse response = stub().writeSchema(
                    WriteSchemaRequest.newBuilder()
                            .setSchema(schema)
                            .build());
            refresh();
            return new SchemaWriteResult(
                    response.hasWrittenAt() ? response.getWrittenAt().getToken() : null);
        } catch (StatusRuntimeException e) {
            throw GrpcExceptionMapper.map(e);
        }
    }

    /**
     * Compares the current SpiceDB schema with a candidate schema.
     *
     * @param comparisonSchema schema DSL text to compare with the server state;
     *                         must not be blank
     * @return stable SDK diff entries and the read revision token, when
     * returned
     * @throws IllegalArgumentException when {@code comparisonSchema} is blank
     * @throws UnsupportedOperationException when this client was not built with
     *                                       a live SpiceDB connection
     */
    public SchemaDiffResult diffRaw(String comparisonSchema) {
        Objects.requireNonNull(comparisonSchema, "comparisonSchema");
        if (comparisonSchema.isBlank()) {
            throw new IllegalArgumentException("comparisonSchema must not be blank");
        }

        try {
            com.authzed.api.v1.DiffSchemaResponse response = stub().diffSchema(
                    DiffSchemaRequest.newBuilder()
                            .setConsistency(com.authzed.api.v1.Consistency.newBuilder()
                                    .setFullyConsistent(true)
                                    .build())
                            .setComparisonSchema(comparisonSchema)
                            .build());
            return new SchemaDiffResult(
                    response.getDiffsList().stream().map(SchemaClient::toDiff).toList(),
                    response.hasReadAt() ? response.getReadAt().getToken() : null);
        } catch (StatusRuntimeException e) {
            throw GrpcExceptionMapper.map(e);
        }
    }

    /**
     * Reloads schema metadata into the SDK cache from a live SpiceDB server.
     *
     * @return {@code true} when the cache was updated; {@code false} when the
     * server does not support reflection or the refresh failed non-fatally
     * @throws UnsupportedOperationException when this client was not built with
     *                                       a live SpiceDB connection
     */
    public boolean refresh() {
        requireRemote();
        if (cache == null || loader == null) {
            return false;
        }
        return loader.load(channel, authMetadata, cache);
    }

    private SchemaServiceGrpc.SchemaServiceBlockingStub stub() {
        requireRemote();
        return SchemaServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMetadata));
    }

    private void requireRemote() {
        if (channel == null || authMetadata == null) {
            throw new UnsupportedOperationException(
                    "Schema management requires a live SpiceDB client");
        }
    }

    private static SchemaDiff toDiff(ReflectionSchemaDiff diff) {
        String kind = diff.getDiffCase().name();
        String target = switch (diff.getDiffCase()) {
            case DEFINITION_ADDED -> definitionTarget(diff.getDefinitionAdded());
            case DEFINITION_REMOVED -> definitionTarget(diff.getDefinitionRemoved());
            case DEFINITION_DOC_COMMENT_CHANGED -> definitionTarget(diff.getDefinitionDocCommentChanged());
            case RELATION_ADDED -> relationTarget(diff.getRelationAdded());
            case RELATION_REMOVED -> relationTarget(diff.getRelationRemoved());
            case RELATION_DOC_COMMENT_CHANGED -> relationTarget(diff.getRelationDocCommentChanged());
            case RELATION_SUBJECT_TYPE_ADDED -> relationSubjectTarget(diff.getRelationSubjectTypeAdded());
            case RELATION_SUBJECT_TYPE_REMOVED -> relationSubjectTarget(diff.getRelationSubjectTypeRemoved());
            case PERMISSION_ADDED -> permissionTarget(diff.getPermissionAdded());
            case PERMISSION_REMOVED -> permissionTarget(diff.getPermissionRemoved());
            case PERMISSION_DOC_COMMENT_CHANGED -> permissionTarget(diff.getPermissionDocCommentChanged());
            case PERMISSION_EXPR_CHANGED -> permissionTarget(diff.getPermissionExprChanged());
            case CAVEAT_ADDED -> caveatTarget(diff.getCaveatAdded());
            case CAVEAT_REMOVED -> caveatTarget(diff.getCaveatRemoved());
            case CAVEAT_DOC_COMMENT_CHANGED -> caveatTarget(diff.getCaveatDocCommentChanged());
            case CAVEAT_EXPR_CHANGED -> caveatTarget(diff.getCaveatExprChanged());
            case CAVEAT_PARAMETER_ADDED -> caveatParameterTarget(diff.getCaveatParameterAdded());
            case CAVEAT_PARAMETER_REMOVED -> caveatParameterTarget(diff.getCaveatParameterRemoved());
            case CAVEAT_PARAMETER_TYPE_CHANGED -> caveatParameterTypeTarget(diff.getCaveatParameterTypeChanged());
            case DIFF_NOT_SET -> "";
        };
        return new SchemaDiff(kind, target);
    }

    private static String definitionTarget(ReflectionDefinition definition) {
        return definition.getName();
    }

    private static String relationTarget(ReflectionRelation relation) {
        return relation.getParentDefinitionName() + "#" + relation.getName();
    }

    private static String relationSubjectTarget(ReflectionRelationSubjectTypeChange change) {
        return relationTarget(change.getRelation());
    }

    private static String permissionTarget(ReflectionPermission permission) {
        return permission.getParentDefinitionName() + "#" + permission.getName();
    }

    private static String caveatTarget(ReflectionCaveat caveat) {
        return caveat.getName();
    }

    private static String caveatParameterTarget(ReflectionCaveatParameter parameter) {
        return parameter.getParentCaveatName() + "." + parameter.getName();
    }

    private static String caveatParameterTypeTarget(ReflectionCaveatParameterTypeChange change) {
        return caveatParameterTarget(change.getParameter());
    }
}
