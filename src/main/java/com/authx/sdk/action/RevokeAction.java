package com.authx.sdk.action;

import com.authx.sdk.ResourceType;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fluent action for revoking specific relations from subjects.
 *
 * <p>Subjects come in as programmatic {@link SubjectRef} values or canonical
 * strings ({@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}).
 * The SDK does not assume a default subject type.
 */
public class RevokeAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String[] relations;
    private final @Nullable SchemaCache schemaCache;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public RevokeAction(String resourceType, String resourceId, SdkTransport transport,
                        String[] relations) {
        this(resourceType, resourceId, transport, relations, null);
    }

    /**
     * Internal — constructor used by {@link com.authx.sdk.ResourceHandle} when
     * schema-aware subject validation is available. When {@code schemaCache}
     * is {@code null} or empty, validation is skipped (fail-open) — the
     * action behaves exactly like the 4-arg constructor and the wire call
     * proceeds unchanged.
     */
    public RevokeAction(String resourceType, String resourceId, SdkTransport transport,
                        String[] relations, @Nullable SchemaCache schemaCache) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.relations = relations;
        this.schemaCache = schemaCache;
    }

    /** Revoke the relation(s) from the given {@link SubjectRef subjects}. */
    public RevokeResult from(SubjectRef... subjects) {
        return from(Arrays.asList(subjects));
    }

    /** Collection overload of {@link #from(SubjectRef...)}. */
    public RevokeResult from(Collection<SubjectRef> subjects) {
        return deleteRelationships(List.copyOf(subjects));
    }

    /**
     * Bare-id form with single-type inference. Mirrors
     * {@link GrantAction#to(String)}:
     *
     * <ul>
     *   <li>{@code id} contains {@code ':'} → canonical, forwarded to {@link #from(String...)}</li>
     *   <li>single non-wildcard subject type declared → wrap as {@code type:id}</li>
     *   <li>multi-type / cross-relation disagreement → throw pointing at
     *       {@link #from(ResourceType, String)}</li>
     *   <li>wildcard-only → throw pointing at {@link #fromWildcard(ResourceType)}</li>
     *   <li>no cache / no declared shapes → fall through to canonical parse
     *       which rejects bare ids</li>
     * </ul>
     *
     * @throws IllegalArgumentException when inference is impossible or the
     *         id is a bare string with no schema to infer from.
     */
    public RevokeResult from(String id) {
        if (id.indexOf(':') >= 0) {
            return from(new String[]{id});
        }
        if (schemaCache == null) {
            return from(new String[]{id});
        }
        SubjectType inferred = null;
        for (String rel : relations) {
            List<SubjectType> sts = schemaCache.getSubjectTypes(resourceType, rel);
            if (sts.isEmpty()) {
                return from(new String[]{id});
            }
            if (sts.stream().allMatch(SubjectType::wildcard)) {
                throw new IllegalArgumentException(
                        resourceType + "." + rel + " only accepts wildcards (" + shapes(sts)
                                + "); use fromWildcard(ResourceType) instead");
            }
            var single = SubjectType.inferSingleType(sts);
            if (single.isEmpty()) {
                throw new IllegalArgumentException(
                        "ambiguous subject type for " + resourceType + "." + rel
                                + " (allowed: " + shapes(sts)
                                + "); use from(ResourceType, id) instead");
            }
            if (inferred == null) {
                inferred = single.get();
            } else if (!inferred.type().equals(single.get().type())) {
                throw new IllegalArgumentException(
                        "cannot infer single subject type across " + relations.length
                                + " relations with differing declared types ("
                                + inferred.type() + " vs " + single.get().type()
                                + "); use from(ResourceType, id) instead");
            }
        }
        return from(new String[]{inferred.type() + ":" + id});
    }

    private static String shapes(List<SubjectType> sts) {
        return sts.stream()
                .map(SubjectType::toRef)
                .distinct()
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** Typed subject form: {@code revoke(...).from(User.TYPE, "alice")}. */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    RevokeResult from(ResourceType<R, P> subjectType, String id) {
        return from(new String[]{subjectType.name() + ":" + id});
    }

    /**
     * Typed subject with sub-relation:
     * {@code revoke(...).from(Group.TYPE, "eng", "member")}.
     */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    RevokeResult from(ResourceType<R, P> subjectType, String id, String subjectRelation) {
        return from(new String[]{subjectType.name() + ":" + id + "#" + subjectRelation});
    }

    /** Wildcard typed form: {@code revoke(...).fromWildcard(User.TYPE)}. */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    RevokeResult fromWildcard(ResourceType<R, P> subjectType) {
        return from(new String[]{subjectType.name() + ":*"});
    }

    /**
     * Typed batch form: same subject type, many ids. Mirrors
     * {@link GrantAction#to(ResourceType, Iterable)}.
     */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    RevokeResult from(ResourceType<R, P> subjectType, Iterable<String> ids) {
        List<String> refs = new ArrayList<>();
        for (String id : ids) refs.add(subjectType.name() + ":" + id);
        return from(refs.toArray(String[]::new));
    }

    /**
     * Revoke the relation(s) from the given canonical subject strings.
     * See {@link GrantAction#to(String...)} for the accepted format.
     *
     * @throws IllegalArgumentException if any string is not a valid subject ref
     */
    public RevokeResult from(String... subjectRefs) {
        return deleteRelationships(Arrays.stream(subjectRefs).map(SubjectRef::parse).toList());
    }

    /**
     * {@link Iterable} overload of {@link #from(String...)} — accepts
     * {@code List<String>} / {@code Set<String>} / any other
     * {@code Iterable<String>} without array conversion at the call site.
     */
    public RevokeResult from(Iterable<String> subjectRefs) {
        List<SubjectRef> subjects = new ArrayList<>();
        for (String ref : subjectRefs) subjects.add(SubjectRef.parse(ref));
        return deleteRelationships(subjects);
    }

    private RevokeResult deleteRelationships(List<SubjectRef> subjects) {
        // Schema-aware subject validation — fail-fast before the RPC so
        // the caller sees a descriptive error, not a gRPC INVALID_ARGUMENT
        // with just the offending relation name. Same policy as
        // GrantAction: fail-open on null / empty cache, fail-fast only
        // when the relation is declared and the subject shape mismatches.
        if (schemaCache != null) {
            for (String rel : relations) {
                for (SubjectRef sub : subjects) {
                    schemaCache.validateSubject(resourceType, rel, sub.toRefString());
                }
            }
        }
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        List<RelationshipUpdate> updates = new ArrayList<>();
        for (String rel : relations) {
            for (SubjectRef sub : subjects) {
                updates.add(new RelationshipUpdate(
                        Operation.DELETE,
                        resource,
                        Relation.of(rel),
                        sub));
            }
        }
        return transport.deleteRelationships(updates);
    }
}
