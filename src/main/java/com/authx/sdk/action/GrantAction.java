package com.authx.sdk.action;

import com.authx.sdk.ResourceType;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.CaveatRef;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final @Nullable SchemaCache schemaCache;
    private Instant expiresAt;
    private CaveatRef caveat;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public GrantAction(String resourceType, String resourceId, SdkTransport transport,
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
    public GrantAction(String resourceType, String resourceId, SdkTransport transport,
                       String[] relations, @Nullable SchemaCache schemaCache) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.relations = relations;
        this.schemaCache = schemaCache;
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
     * Bare-id form with single-type inference. Resolves as follows:
     *
     * <ol>
     *   <li>If {@code id} contains {@code ':'} it is treated as canonical
     *       and forwarded to {@link #to(String...)}.</li>
     *   <li>Otherwise — and only when a {@link SchemaCache} is attached —
     *       the SDK inspects each target relation's declared subject
     *       types. When <b>every</b> relation declares exactly one
     *       non-wildcard type (wildcards are ignored) <i>and</i> all
     *       relations agree on the same type, the id is wrapped as
     *       {@code inferredType:id}.</li>
     *   <li>If inference is ambiguous (multi-type relation) the call
     *       throws with a message naming the allowed shapes and
     *       pointing at {@link #to(com.authx.sdk.ResourceType, String)}.</li>
     *   <li>If the relation is wildcard-only the call throws pointing
     *       at {@link #toWildcard(com.authx.sdk.ResourceType)}.</li>
     *   <li>When no cache is attached (e.g. {@code loadSchemaOnStart(false)})
     *       the bare id falls through to the canonical-parse path and is
     *       rejected by {@link SubjectRef#parse(String)} for lack of a
     *       type prefix.</li>
     * </ol>
     *
     * <p>This overload is the "sugar" path for business code that knows
     * the relation locks the subject type. It is intentionally <b>not</b>
     * a fallback — inference refuses to guess a default subject type so
     * call sites stay honest.
     *
     * @throws IllegalArgumentException when inference is impossible
     *         (ambiguous / wildcard-only) or when the id is a bare string
     *         with no schema to infer from.
     */
    public GrantResult to(String id) {
        // 1) Canonical form → hand to the varargs path directly.
        if (id.indexOf(':') >= 0) {
            return to(new String[]{id});
        }
        // 2) No schema → nothing to infer from. Delegate to the
        //    canonical path which will reject the bare id.
        if (schemaCache == null) {
            return to(new String[]{id});
        }
        SubjectType inferred = null;
        for (String rel : relations) {
            List<SubjectType> sts = schemaCache.getSubjectTypes(resourceType, rel);
            if (sts.isEmpty()) {
                // No declared shape — nothing to infer from. Fall through
                // to canonical parse (which will throw for a bare id).
                return to(new String[]{id});
            }
            if (sts.stream().allMatch(SubjectType::wildcard)) {
                throw new IllegalArgumentException(
                        resourceType + "." + rel + " only accepts wildcards (" + shapes(sts)
                                + "); use toWildcard(ResourceType) instead");
            }
            var single = SubjectType.inferSingleType(sts);
            if (single.isEmpty()) {
                throw new IllegalArgumentException(
                        "ambiguous subject type for " + resourceType + "." + rel
                                + " (allowed: " + shapes(sts)
                                + "); use to(ResourceType, id) instead");
            }
            if (inferred == null) {
                inferred = single.get();
            } else if (!inferred.type().equals(single.get().type())) {
                throw new IllegalArgumentException(
                        "cannot infer single subject type across " + relations.length
                                + " relations with differing declared types ("
                                + inferred.type() + " vs " + single.get().type()
                                + "); use to(ResourceType, id) instead");
            }
        }
        // inferred is guaranteed non-null here (relations is non-empty and
        // the `sts.isEmpty()` / empty early-returns would have fired above).
        String canonical = inferred.type() + ":" + id;
        return to(new String[]{canonical});
    }

    private static String shapes(List<SubjectType> sts) {
        return sts.stream()
                .map(SubjectType::toRef)
                .distinct()
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Typed subject form: {@code grant(...).to(User.TYPE, "alice")} —
     * constructs the canonical {@code "user:alice"} ref and routes through
     * {@link #to(String...)} so the schema validation runs as normal.
     *
     * <p>Use this when the schema has multiple declared subject types on
     * the relation (so bare-id inference refuses to guess) and you want
     * to be explicit about which one you mean without hand-crafting a
     * canonical string.
     */
    public <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    GrantResult to(ResourceType<R, P> subjectType, String id) {
        return to(new String[]{subjectType.name() + ":" + id});
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
        // Schema-aware subject validation — fail-fast before the RPC so
        // the caller sees a descriptive error, not a gRPC INVALID_ARGUMENT
        // with just the offending relation name.
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
