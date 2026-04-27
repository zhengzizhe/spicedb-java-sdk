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
 * Fluent action for granting relations within a batch operation.
 *
 * <p>Subjects come in as programmatic {@link SubjectRef} values or canonical
 * strings. The SDK does not assume a default subject type.
 */
public class BatchGrantAction {
    private final BatchBuilder batch;
    private final String resourceType;
    private final String resourceId;
    private final String[] relations;
    private final @Nullable SchemaCache schemaCache;

    /** Internal — use {@link BatchBuilder#grant(String...)} entry point. */
    public BatchGrantAction(BatchBuilder batch, String resourceType, String resourceId,
                            String[] relations) {
        this(batch, resourceType, resourceId, relations, null);
    }

    /**
     * Internal — schema-aware constructor. When {@code schemaCache} is
     * {@code null} or empty, validation is skipped (fail-open).
     */
    public BatchGrantAction(BatchBuilder batch, String resourceType, String resourceId,
                            String[] relations, @Nullable SchemaCache schemaCache) {
        this.batch = batch;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.relations = relations;
        this.schemaCache = schemaCache;
    }

    /** Grant the relation(s) to the given {@link SubjectRef subjects}. */
    public BatchBuilder to(SubjectRef... subjects) {
        validate(subjects);
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (SubjectRef sub : subjects) {
                batch.addUpdate(new RelationshipUpdate(
                        Operation.TOUCH, resource, Relation.of(rel), sub));
            }
        }
        return batch;
    }

    /**
     * Grant the relation(s) to the given canonical subject strings.
     * See {@link GrantAction#to(String...)} for the accepted format.
     */
    public BatchBuilder to(String... subjectRefs) {
        SubjectRef[] parsed = new SubjectRef[subjectRefs.length];
        for (int i = 0; i < subjectRefs.length; i++) parsed[i] = SubjectRef.parse(subjectRefs[i]);
        return to(parsed);
    }

    /** {@link Iterable} overload of {@link #to(String...)}. */
    public BatchBuilder to(Iterable<String> subjectRefs) {
        List<SubjectRef> parsed = new ArrayList<>();
        for (String ref : subjectRefs) parsed.add(SubjectRef.parse(ref));
        return to(parsed.toArray(SubjectRef[]::new));
    }

    // Validation is centralised on the SubjectRef array path so all three
    // to(...) overloads (SubjectRef / String... / Iterable<String>) route
    // through the same check — including parsed canonical strings.
    private void validate(SubjectRef[] subjects) {
        if (schemaCache == null) return;
        for (String rel : relations) {
            for (SubjectRef sub : subjects) {
                schemaCache.validateSubject(resourceType, rel, sub.toRefString());
            }
        }
    }
}
