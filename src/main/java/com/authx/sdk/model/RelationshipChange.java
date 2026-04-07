package com.authx.sdk.model;

/**
 * A single relationship change received from the SpiceDB Watch stream.
 *
 * <pre>
 * client.onRelationshipChange(change -&gt; {
 *     System.out.println(change.resourceType() + ":" + change.resourceId()
 *         + " " + change.operation() + " " + change.relation()
 *         + " -> " + change.subjectType() + ":" + change.subjectId());
 *     // e.g. "document:doc-1 TOUCH editor -> user:alice"
 * });
 * </pre>
 *
 * @param operation       whether the relationship was created/updated ({@code TOUCH}) or removed ({@code DELETE})
 * @param resourceType    the resource object type
 * @param resourceId      the resource object id
 * @param relation        the relation name
 * @param subjectType     the subject object type
 * @param subjectId       the subject object id
 * @param subjectRelation the subject relation (may be empty)
 * @param zedToken        the ZedToken at which this change was observed
 */
public record RelationshipChange(
        Operation operation,
        String resourceType,
        String resourceId,
        String relation,
        String subjectType,
        String subjectId,
        String subjectRelation,
        String zedToken
) {
    /** The type of mutation applied to a relationship. */
    public enum Operation {
        /** The relationship was created or updated (idempotent upsert). */
        TOUCH,
        /** The relationship was deleted (idempotent removal). */
        DELETE
    }
}
