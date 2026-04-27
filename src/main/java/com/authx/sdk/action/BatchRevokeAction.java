package com.authx.sdk.action;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Fluent action for revoking relations within a batch operation.
 *
 * <p>Subjects come in as programmatic {@link SubjectRef} values or canonical
 * strings. The SDK does not assume a default subject type.
 */
public class BatchRevokeAction {
    private final BatchBuilder batch;
    private final String resourceType;
    private final String resourceId;
    private final String[] relations;
    private final @Nullable SchemaCache schemaCache;

    /** Internal — use {@link BatchBuilder#revoke(String...)} entry point. */
    public BatchRevokeAction(BatchBuilder batch, String resourceType, String resourceId,
                             String[] relations) {
        this(batch, resourceType, resourceId, relations, null);
    }

    /**
     * Internal — schema-aware constructor. When {@code schemaCache} is
     * {@code null} or empty, validation is skipped (fail-open).
     */
    public BatchRevokeAction(BatchBuilder batch, String resourceType, String resourceId,
                             String[] relations, @Nullable SchemaCache schemaCache) {
        this.batch = batch;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.relations = relations;
        this.schemaCache = schemaCache;
    }

    /** Revoke the relation(s) from the given {@link SubjectRef subjects}. */
    public BatchBuilder from(SubjectRef... subjects) {
        validate(subjects);
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (SubjectRef sub : subjects) {
                batch.addUpdate(new RelationshipUpdate(
                        Operation.DELETE, resource, Relation.of(rel), sub));
            }
        }
        return batch;
    }

    /**
     * Revoke the relation(s) from the given canonical subject strings.
     * See {@link GrantAction#to(String...)} for the accepted format.
     */
    public BatchBuilder from(String... subjectRefs) {
        SubjectRef[] parsed = new SubjectRef[subjectRefs.length];
        for (int i = 0; i < subjectRefs.length; i++) parsed[i] = SubjectRef.parse(subjectRefs[i]);
        return from(parsed);
    }

    /** {@link Iterable} overload of {@link #from(String...)}. */
    public BatchBuilder from(Iterable<String> subjectRefs) {
        List<SubjectRef> parsed = new ArrayList<>();
        for (String ref : subjectRefs) parsed.add(SubjectRef.parse(ref));
        return from(parsed.toArray(SubjectRef[]::new));
    }

    // Validation is centralised on the SubjectRef array path so all three
    // from(...) overloads route through the same check.
    private void validate(SubjectRef[] subjects) {
        if (schemaCache == null) return;
        for (String rel : relations) {
            for (SubjectRef sub : subjects) {
                schemaCache.validateSubject(resourceType, rel, sub.toRefString());
            }
        }
    }
}
