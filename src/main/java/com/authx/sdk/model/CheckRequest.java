package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/** Immutable check request. Replaces 6-7 scattered String parameters in SdkTransport.check(). */
public record CheckRequest(
    ResourceRef resource,
    Permission permission,
    SubjectRef subject,
    Consistency consistency,
    @Nullable Map<String, Object> caveatContext
) {
    public CheckRequest {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(consistency, "consistency");
    }

    public static CheckRequest of(ResourceRef resource, Permission permission, SubjectRef subject, Consistency consistency) {
        return new CheckRequest(resource, permission, subject, consistency, null);
    }

    /** Bridge from legacy String params (used internally by AuthxClient). */
    public static CheckRequest of(String resourceType, String resourceId, String permission,
                                     String subjectType, String subjectId, Consistency consistency) {
        return new CheckRequest(
            ResourceRef.of(resourceType, resourceId),
            Permission.of(permission),
            SubjectRef.of(subjectType, subjectId, null),
            consistency, null);
    }

    /** Bridge with subject relation support. */
    public static CheckRequest of(String resourceType, String resourceId, String permission,
                                     String subjectType, String subjectId, String subjectRelation,
                                     Consistency consistency) {
        return new CheckRequest(
            ResourceRef.of(resourceType, resourceId),
            Permission.of(permission),
            SubjectRef.of(subjectType, subjectId, subjectRelation),
            consistency, null);
    }

    public CheckRequest withConsistency(Consistency c) {
        return c == consistency ? this : new CheckRequest(resource, permission, subject, c, caveatContext);
    }

    public CheckKey toKey() {
        return new CheckKey(resource, permission, subject);
    }
}
