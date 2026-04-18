package com.authx.sdk.action;

import com.authx.sdk.model.CaveatRef;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Fluent action for granting one or more relations on a resource to subjects.
 */
public class GrantAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final String[] relations;
    private Instant expiresAt;
    private CaveatRef caveat;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public GrantAction(String resourceType, String resourceId, SdkTransport transport,
                       String defaultSubjectType, String[] relations) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.relations = relations;
    }

    /** Set expiration time for the granted relationships. */
    public GrantAction expiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
        return this;
    }

    /** Set expiration as a duration from now. */
    public GrantAction expiresIn(Duration duration) {
        this.expiresAt = Instant.now().plus(duration);
        return this;
    }

    /** Attach a caveat (conditional permission). */
    public GrantAction withCaveat(String caveatName, Map<String, Object> context) {
        this.caveat = new CaveatRef(caveatName, context);
        return this;
    }

    /** Attach a caveat using a generated {@link CaveatRef}. */
    public GrantAction withCaveat(CaveatRef ref) {
        this.caveat = ref;
        return this;
    }

    /** Alias for {@link #withCaveat(CaveatRef)} — reads as "grant member onlyIf ...". */
    public GrantAction onlyIf(CaveatRef ref) { return withCaveat(ref); }

    /** Alias for {@link #withCaveat(String, Map)} — reads as "grant member onlyIf ...". */
    public GrantAction onlyIf(String caveatName, Map<String, Object> context) { return withCaveat(caveatName, context); }

    /** Grant the relation(s) to the given user ids and execute the write. */
    public GrantResult to(String... userIds) {
        return to(Arrays.asList(userIds));
    }

    /** Grant the relation(s) to the given user ids and execute the write. */
    public GrantResult to(Collection<String> userIds) {
        return writeRelationships(userIds.stream()
                .map(id -> SubjectRef.of(defaultSubjectType, id, null))
                .toList());
    }

    /** Grant the relation(s) to the given subject refs (e.g., {@code "department:eng#all_members"}). */
    public GrantResult toSubjects(String... subjectRefs) {
        return toSubjects(Arrays.asList(subjectRefs));
    }

    /** Grant the relation(s) to the given subject refs (e.g., {@code "department:eng#all_members"}). */
    public GrantResult toSubjects(Collection<String> subjectRefs) {
        return writeRelationships(subjectRefs.stream().map(SubjectRef::parse).toList());
    }

    private GrantResult writeRelationships(List<SubjectRef> subjects) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        List<RelationshipUpdate> updates = new ArrayList<>();
        for (String rel : relations) {
            for (SubjectRef sub : subjects) {
                updates.add(new RelationshipUpdate(
                        Operation.TOUCH,
                        resource,
                        Relation.of(rel),
                        sub,
                        caveat, expiresAt));
            }
        }
        return transport.writeRelationships(updates);
    }
}
