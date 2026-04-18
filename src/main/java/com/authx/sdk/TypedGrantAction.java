package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.CaveatRef;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectRef;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Typed grant action — grants one or more relations on one or more resources
 * to one or more subjects. Every subject method ({@link #toUser},
 * {@link #toGroupMember}, {@link #to(SubjectRef...)}, ...) validates against
 * the loaded SpiceDB schema at call time via {@link SchemaCache}, so an
 * incompatible subject type (e.g. granting a {@code folder} subject to an
 * {@code editor} relation that only accepts users) raises a clear
 * {@link IllegalArgumentException} instead of shipping a broken request.
 *
 * <p>This class lives in the SDK core rather than being emitted per-resource
 * by codegen: because validation is runtime, the generator doesn't need to
 * know which subjects each relation accepts, and the generated type class
 * becomes a pure static API over the SDK's typed chain.
 *
 * <pre>
 * doc.select("doc-1").grant(Document.Rel.EDITOR).toUser("bob");
 * doc.select("doc-1").grant(Document.Rel.EDITOR).toGroupMember("eng");
 * doc.select("doc-1").grant(Document.Rel.FOLDER).toFolder("f-1");        // cross-type
 * doc.select("doc-1").grant(Document.Rel.EDITOR).toFolder("f-1");        // IllegalArgumentException
 * doc.select("doc-1").grant(Document.Rel.VIEWER).toUserAll();            // user:*
 *
 * // Arbitrary subject type via SubjectRef — handles cross-type grants
 * task.select("t-1").grant(Task.Rel.DOCUMENT).to(SubjectRef.of("document", "doc-5", null));
 * </pre>
 */
public class TypedGrantAction<R extends Relation.Named> {

    protected final ResourceFactory factory;
    protected final String[] ids;
    protected final R[] relations;

    // Optional caveat & expiration — set via withCaveat / expiringAt before
    // the terminal toXxx call. Both travel through to RelationshipUpdate.
    private String caveatName;
    private Map<String, Object> caveatContext;
    private Instant expiresAt;

    @SafeVarargs
    public TypedGrantAction(ResourceFactory factory, String[] ids, R... relations) {
        this.factory = factory;
        this.ids = ids;
        this.relations = relations;
    }

    // ════════════════════════════════════════════════════════════════
    //  Caveat + expiration (chainable before the terminal toXxx)
    // ════════════════════════════════════════════════════════════════

    /**
     * Attach a caveat (conditional permission) to the grants this action
     * will write. {@code caveatName} must match a caveat defined in the
     * SpiceDB schema; {@code context} carries the caveat's static
     * variables (values known at grant time). Dynamic variables come later
     * at check time via {@code check(perm).withContext(...)}.
     *
     * <pre>
     * doc.select("doc-1").grant(Document.Rel.EDITOR)
     *     .withCaveat("ip_range", Map.of("allowed_cidr", "10.0.0.0/8"))
     *     .toUser("alice");
     * </pre>
     */
    public TypedGrantAction<R> withCaveat(String caveatName, Map<String, Object> context) {
        this.caveatName = caveatName;
        this.caveatContext = context;
        return this;
    }

    /** Attach a caveat using a generated {@link CaveatRef}. */
    public TypedGrantAction<R> withCaveat(CaveatRef ref) {
        this.caveatName = ref.name();
        this.caveatContext = ref.context();
        return this;
    }

    /** Alias for {@link #withCaveat(CaveatRef)} — reads as "grant member onlyIf ...". */
    public TypedGrantAction<R> onlyIf(CaveatRef ref) { return withCaveat(ref); }

    /** Alias for {@link #withCaveat(String, Map)} — reads as "grant member onlyIf ...". */
    public TypedGrantAction<R> onlyIf(String caveatName, Map<String, Object> context) { return withCaveat(caveatName, context); }

    /**
     * Attach an expiration timestamp. Past this instant SpiceDB treats the
     * relationship as revoked — permission computations will not include
     * it. Requires SpiceDB 1.35+; older servers reject the write.
     */
    public TypedGrantAction<R> expiringAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
        return this;
    }

    /** Convenience: relative expiration from now. */
    public TypedGrantAction<R> expiringIn(Duration duration) {
        this.expiresAt = Instant.now().plus(duration);
        return this;
    }

    // ════════════════════════════════════════════════════════════════
    //  Common subject-type shortcuts (validated against schema)
    // ════════════════════════════════════════════════════════════════

    /** Grant to one or more user ids — equivalent to {@code user:<id>} subjects. */
    public void toUser(String... userIds) {
        writeTypedSubjects("user", null, userIds);
    }

    public void toUser(Collection<String> userIds) {
        toUser(userIds.toArray(String[]::new));
    }

    /**
     * Grant to one or more group-member subjects — equivalent to
     * {@code group:<id>#member}. Use this when the schema declares the
     * relation as accepting {@code group#member} references.
     */
    public void toGroupMember(String... groupIds) {
        writeTypedSubjects("group", "member", groupIds);
    }

    public void toGroupMember(Collection<String> groupIds) {
        toGroupMember(groupIds.toArray(String[]::new));
    }

    /**
     * Grant to the "everyone" wildcard — equivalent to granting
     * {@code user:*}. The schema's relation must declare {@code user:*}
     * as an allowed subject for this to pass validation.
     */
    public void toUserAll() {
        write(new String[]{"user:*"});
    }

    // ════════════════════════════════════════════════════════════════
    //  Generic subject entry — supports any subject type in the schema
    // ════════════════════════════════════════════════════════════════

    /**
     * Grant to one or more {@link SubjectRef}s of arbitrary type. This is the
     * canonical entry point for cross-type grants (task → document, document
     * → folder, etc.) and anything that doesn't have a first-class
     * convenience method. Each subject is validated against the schema's
     * declared subject types for every relation in this action.
     *
     * <pre>
     * // Cross-type: task's "document" relation accepts a document subject
     * task.select("t-1").grant(Task.Rel.DOCUMENT)
     *     .to(SubjectRef.of("document", "doc-5", null));
     *
     * // Department#all_members subject
     * doc.select("doc-1").grant(Document.Rel.VIEWER)
     *     .to(SubjectRef.of("department", "eng", "all_members"));
     * </pre>
     */
    public void to(SubjectRef... subjects) {
        if (subjects == null || subjects.length == 0) return;
        String[] refs = new String[subjects.length];
        for (int i = 0; i < subjects.length; i++) {
            refs[i] = subjects[i].toRefString();
        }
        write(refs);
    }

    public void to(Collection<SubjectRef> subjects) {
        to(subjects.toArray(SubjectRef[]::new));
    }

    /**
     * Raw-string escape hatch for dynamic scenarios where you already have
     * {@code "type:id"} / {@code "type:id#relation"} / {@code "type:*"}
     * strings. Still validated against the schema at runtime.
     */
    public void toSubjectRefs(String... subjectRefs) {
        if (subjectRefs == null || subjectRefs.length == 0) return;
        write(subjectRefs);
    }

    /** Collection overload for {@link #toSubjectRefs(String...)}. */
    public void toSubjectRefs(Collection<String> subjectRefs) {
        if (subjectRefs == null || subjectRefs.isEmpty()) return;
        write(subjectRefs.toArray(String[]::new));
    }

    // ════════════════════════════════════════════════════════════════
    //  Internals
    // ════════════════════════════════════════════════════════════════

    private void writeTypedSubjects(String type, String subRelation, String[] ids) {
        if (ids == null || ids.length == 0) return;
        String[] refs = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            refs[i] = (subRelation == null || subRelation.isEmpty())
                    ? type + ":" + ids[i]
                    : type + ":" + ids[i] + "#" + subRelation;
        }
        write(refs);
    }

    private void write(String[] refs) {
        // Runtime subject-type validation. If the schema cache is empty or
        // disabled, validateSubject is a no-op — we cannot reject what we
        // cannot verify. In a normal client, the cache is populated at
        // startup and every relation's allowed subjects are known.
        SchemaCache schema = factory.schemaCache();
        if (schema != null) {
            String resourceType = factory.resourceType();
            for (R rel : relations) {
                String relName = rel.relationName();
                for (String ref : refs) {
                    schema.validateSubject(resourceType, relName, ref);
                }
            }
        }

        // Route through GrantAction so caveat + expiration propagate into
        // the RelationshipUpdate written by the transport. The simple path
        // (no caveat, no expiration) is a no-op extra allocation compared
        // to the former direct call to factory.grantToSubjects, which is
        // cheap enough to always take — the savings wouldn't be observable
        // even at 100k writes/sec.
        for (String id : ids) {
            for (R rel : relations) {
                var action = factory.resource(id).grant(rel.relationName());
                if (caveatName != null) {
                    action.withCaveat(caveatName, caveatContext);
                }
                if (expiresAt != null) {
                    action.expiresAt(expiresAt);
                }
                action.toSubjects(refs);
            }
        }
    }
}
