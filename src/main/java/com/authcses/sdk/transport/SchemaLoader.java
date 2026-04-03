package com.authcses.sdk.transport;

import com.authcses.sdk.cache.SchemaCache;
import com.authzed.api.v1.ExperimentalReflectSchemaRequest;
import com.authzed.api.v1.ExperimentalServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Loads schema directly from SpiceDB's ExperimentalReflectSchema gRPC API.
 * No platform dependency.
 */
public class SchemaLoader {

    private static final System.Logger LOG = System.getLogger(SchemaLoader.class.getName());

    /**
     * Load schema from SpiceDB and populate the SchemaCache.
     * Non-fatal: returns false if loading fails (SDK can still work without schema validation).
     */
    public static boolean load(ManagedChannel channel, Metadata authMetadata, SchemaCache schemaCache) {
        try {
            var stub = ExperimentalServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMetadata));

            var response = stub.experimentalReflectSchema(
                    ExperimentalReflectSchemaRequest.newBuilder()
                            .setConsistency(com.authzed.api.v1.Consistency.newBuilder()
                                    .setFullyConsistent(true))
                            .build());

            Map<String, SchemaCache.DefinitionCache> definitions = new HashMap<>();
            for (var def : response.getDefinitionsList()) {
                Set<String> relations = new HashSet<>();
                for (var rel : def.getRelationsList()) {
                    relations.add(rel.getName());
                }
                Set<String> permissions = new HashSet<>();
                for (var perm : def.getPermissionsList()) {
                    permissions.add(perm.getName());
                }
                definitions.put(def.getName(), new SchemaCache.DefinitionCache(relations, permissions));
            }
            schemaCache.updateFromMap(definitions);
            LOG.log(System.Logger.Level.INFO, "Schema loaded: {0} definitions", definitions.size());
            return true;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Schema load failed (non-fatal): {0}", e.getMessage());
            return false;
        }
    }
}
