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
 *
 * <h2>Subject input forms</h2>
 *
 * <ul>
 *   <li>{@link #to(SubjectRef...)} — programmatic subjects (from variables,
 *       typed identifiers, or {@link SubjectRef#of(String, String)})</li>
 *   <li>{@link #to(String...)} — canonical subject strings:
 *       {@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}</li>
 * </ul>
 *
 * Strings are always in canonical {@code type:id} / {@code type:id#relation}
 * format — the SDK does not assume a default subject type.
 */
public class GrantAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String[] relations;
    private Instant expiresAt;
    private CaveatRef caveat;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public GrantAction(String resourceType, String resourceId, SdkTransport transport,
                       String[] relations) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
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

    /**
     * Grant the relation(s) to the given {@link SubjectRef subjects} and
     * execute the write.
     */
    public GrantResult to(SubjectRef... subjects) {
        return to(Arrays.asList(subjects));
    }

    /** Collection overload of {@link #to(SubjectRef...)}. */
    public GrantResult to(Collection<SubjectRef> subjects) {
        return writeRelationships(List.copyOf(subjects));
    }

    /**
     * Grant the relation(s) to the given canonical subject strings and
     * execute the write.
     *
     * <p>Each string must be in SpiceDB canonical format:
     * {@code "type:id"}, {@code "type:id#relation"}, or {@code "type:*"}.
     * There is no default subject type — {@code "alice"} is rejected;
     * write {@code "user:alice"}.
     *
     * @throws IllegalArgumentException if any string is not a valid subject ref
     */
    public GrantResult to(String... subjectRefs) {
        return writeRelationships(Arrays.stream(subjectRefs).map(SubjectRef::parse).toList());
    }

    /**
     * {@link Iterable} overload of {@link #to(String...)} — accepts
     * {@code List<String>} / {@code Set<String>} / any other
     * {@code Iterable<String>} without the caller having to convert to
     * an array.
     *
     * <p>Uses {@code Iterable<String>} (not {@code Collection<String>})
     * so this overload's erasure does not clash with
     * {@link #to(Collection)} — see javadoc there.
     *
     * @throws IllegalArgumentException if any string is not a valid subject ref
     */
    public GrantResult to(Iterable<String> subjectRefs) {
        List<SubjectRef> subjects = new ArrayList<>();
        for (String ref : subjectRefs) subjects.add(SubjectRef.parse(ref));
        return writeRelationships(subjects);
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
