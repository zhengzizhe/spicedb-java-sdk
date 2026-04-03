package com.authcses.sdk.model;

/**
 * A single relationship change received from SpiceDB Watch stream.
 *
 * <pre>
 * client.onRelationshipChange(change -> {
 *     System.out.println(change.resourceType() + ":" + change.resourceId()
 *         + " " + change.operation() + " " + change.relation()
 *         + " → " + change.subjectType() + ":" + change.subjectId());
 *     // e.g. "document:doc-1 TOUCH editor → user:alice"
 * });
 * </pre>
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
    public enum Operation {
        TOUCH,
        DELETE
    }
}
