package com.authx.sdk.transport;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SubjectType;
import com.authzed.api.v1.Consistency;
import com.authzed.api.v1.ExperimentalReflectSchemaRequest;
import com.authzed.api.v1.ExperimentalServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Loads schema metadata from SpiceDB's {@code ExperimentalReflectSchema}
 * gRPC and writes it into {@link SchemaCache}. Non-fatal on failure:
 * returns {@code false}, leaves the cache untouched, and remembers
 * UNIMPLEMENTED so we skip the roundtrip next time.
 */
public class SchemaLoader {

    private static final System.Logger LOG =
            System.getLogger(SchemaLoader.class.getName());

    /** Flipped false once we observe UNIMPLEMENTED. Per-instance. */
    private volatile boolean reflectSupported = true;

    /**
     * Call {@code ExperimentalReflectSchema} and populate {@code cache}.
     *
     * @return {@code true} iff the response was consumed and the cache
     *         was updated. {@code false} for UNIMPLEMENTED, transport
     *         errors, or any parse problem — the SDK keeps running
     *         without schema validation.
     */
    public boolean load(ManagedChannel channel, Metadata authMetadata, SchemaCache cache) {
        if (!reflectSupported) return false;
        try {
            com.authzed.api.v1.ExperimentalServiceGrpc.ExperimentalServiceBlockingStub stub = ExperimentalServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(authMetadata));

            com.authzed.api.v1.ExperimentalReflectSchemaResponse resp = stub.experimentalReflectSchema(
                    ExperimentalReflectSchemaRequest.newBuilder()
                            .setConsistency(Consistency.newBuilder().setFullyConsistent(true))
                            .build());

            Map<String, SchemaCache.DefinitionCache> defs = new HashMap<>();
            for (com.authzed.api.v1.ExpDefinition def : resp.getDefinitionsList()) {
                Set<String> relations = new HashSet<>();
                Map<String, List<SubjectType>> relSTs = new HashMap<>();
                for (com.authzed.api.v1.ExpRelation rel : def.getRelationsList()) {
                    relations.add(rel.getName());
                    List<SubjectType> sts = new ArrayList<>();
                    for (com.authzed.api.v1.ExpTypeReference st : rel.getSubjectTypesList()) {
                        String relName = st.getOptionalRelationName();
                        if (relName != null && relName.isEmpty()) relName = null;
                        sts.add(new SubjectType(
                                st.getSubjectDefinitionName(),
                                relName,
                                st.getIsPublicWildcard()));
                    }
                    relSTs.put(rel.getName(), sts);
                }
                Set<String> permissions = new HashSet<>();
                for (com.authzed.api.v1.ExpPermission perm : def.getPermissionsList()) {
                    permissions.add(perm.getName());
                }
                defs.put(def.getName(), new SchemaCache.DefinitionCache(
                        relations, permissions, relSTs));
            }
            cache.updateFromMap(defs);

            Map<String, SchemaCache.CaveatDef> caveats = new HashMap<>();
            for (com.authzed.api.v1.ExpCaveat cav : resp.getCaveatsList()) {
                Map<String, String> params = new LinkedHashMap<>();
                for (com.authzed.api.v1.ExpCaveatParameter p : cav.getParametersList()) {
                    params.put(p.getName(), p.getType());
                }
                caveats.put(cav.getName(), new SchemaCache.CaveatDef(
                        cav.getName(), params, cav.getExpression(), cav.getComment()));
            }
            cache.updateCaveats(caveats);

            LOG.log(System.Logger.Level.INFO,
                    "Schema loaded: {0} definitions, {1} caveats",
                    defs.size(), caveats.size());
            return true;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNIMPLEMENTED) {
                reflectSupported = false;
                LOG.log(System.Logger.Level.INFO,
                        "ExperimentalReflectSchema unsupported by server — schema validation disabled");
                return false;
            }
            LOG.log(System.Logger.Level.WARNING,
                    "Schema load failed (non-fatal): {0}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Schema load failed (non-fatal): {0}", e.getMessage());
            return false;
        }
    }
}
