package com.authx.sdk.model;

import java.time.Instant;
import java.util.Map;

/**
 * A single relationship change received from the SpiceDB Watch stream.
 *
 * <pre>
 * client.onRelationshipChange(change -&gt; {
 *     System.out.println(change.resourceType() + ":" + change.resourceId()
 *         + " " + change.operation() + " " + change.relation()
 *         + " -&gt; " + change.subjectType() + ":" + change.subjectId());
 *     // e.g. "document:doc-1 TOUCH editor -&gt; user:alice"
 *
 *     // Audit integration: transaction metadata is propagated from the writer.
 *     String actor = change.transactionMetadata().get("actor");
 *     String traceId = change.transactionMetadata().get("trace_id");
 * });
 * </pre>
 *
 * @param operation           mutation kind (CREATE/TOUCH/DELETE)
 * @param resourceType        the resource object type
 * @param resourceId          the resource object id
 * @param relation            the relation name
 * @param subjectType         the subject object type
 * @param subjectId           the subject object id
 * @param subjectRelation     the subject relation (may be {@code null})
 * @param zedToken            the ZedToken at which this change was observed
 * @param caveatName          name of the contextual caveat attached to the relationship, or {@code null}
 * @param expiresAt           relationship expiration time, or {@code null}
 * @param transactionMetadata immutable map of metadata attached to the SpiceDB transaction by the
 *                            writer (e.g. {@code actor}, {@code trace_id}, {@code reason}).
 *                            Never {@code null}; empty if the writer did not attach any.
 */
public record RelationshipChange(
        Operation operation,
        String resourceType,
        String resourceId,
        String relation,
        String subjectType,
        String subjectId,
        String subjectRelation,
        String zedToken,
        String caveatName,
        Instant expiresAt,
        Map<String, String> transactionMetadata
) {
    /** Compact constructor — normalize {@code transactionMetadata} to an immutable non-null map. */
    public RelationshipChange {
        transactionMetadata = (transactionMetadata == null || transactionMetadata.isEmpty())
                ? Map.of()
                : Map.copyOf(transactionMetadata);
    }

    /** The type of mutation applied to a relationship. */
    public enum Operation {
        /** The relationship was created. Only emitted by writers that explicitly use {@code CREATE};
         *  most SDKs (including this one) use {@code TOUCH}, which is idempotent. */
        CREATE,
        /** The relationship was created or updated (idempotent upsert). */
        TOUCH,
        /** The relationship was deleted (idempotent removal). */
        DELETE
    }
}
