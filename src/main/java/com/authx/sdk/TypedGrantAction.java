package com.authx.sdk;

import com.authx.sdk.action.GrantCompletion;
import com.authx.sdk.model.CaveatRef;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectRef;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Typed grant action — grants one or more relations on one or more resources
 * to one or more subjects. Invalid subject types (e.g. granting a
 * {@code folder} subject to an {@code editor} relation that only accepts
 * users) are rejected by SpiceDB and surface as
 * {@link com.authx.sdk.exception.AuthxInvalidArgumentException}. (Pre-ADR
 * 2026-04-18 this was a client-side check via the now-removed
 * {@code SchemaCache}; the rejection moved to the server boundary with
 * the cache subsystem removal.)
 *
 * <p>This class lives in the SDK core rather than being emitted per-resource
 * by codegen: the generator only needs to emit type metadata (enums + the
 * {@code ResourceType} constant), not per-type validation code.
 *
 * <pre>
 * doc.select("doc-1").grant(Document.Rel.EDITOR).toUser("bob");
 * doc.select("doc-1").grant(Document.Rel.EDITOR).toGroupMember("eng");
 * doc.select("doc-1").grant(Document.Rel.FOLDER).toFolder("f-1");   // cross-type
 * doc.select("doc-1").grant(Document.Rel.VIEWER).toUserAll();       // user:*
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
    //  Terminal methods — subjects come in as SubjectRef or canonical strings
    // ════════════════════════════════════════════════════════════════

    /**
     * Grant to one or more {@link SubjectRef subjects}.
     *
     * <pre>
     * // Cross-type: task's "document" relation accepts a document subject
     * task.select("t-1").grant(Task.Rel.DOCUMENT)
     *     .to(SubjectRef.of("document", "doc-5"));
     *
     * // Department#all_members subject
     * doc.select("doc-1").grant(Document.Rel.VIEWER)
     *     .to(SubjectRef.of("department", "eng", "all_members"));
     * </pre>
     */
    public GrantCompletion to(SubjectRef... subjects) {
        if (subjects == null || subjects.length == 0) {
            return GrantCompletion.of(new GrantResult(null, 0));
        }
        String[] refs = new String[subjects.length];
        for (int i = 0; i < subjects.length; i++) {
            refs[i] = subjects[i].toRefString();
        }
        return write(refs);
    }

    /** Collection overload of {@link #to(SubjectRef...)}. */
    public GrantCompletion to(Collection<SubjectRef> subjects) {
        return to(subjects.toArray(SubjectRef[]::new));
    }

    /**
     * Grant to one or more canonical subject strings:
     * {@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}.
     */
    public GrantCompletion to(String... subjectRefs) {
        if (subjectRefs == null || subjectRefs.length == 0) {
            return GrantCompletion.of(new GrantResult(null, 0));
        }
        return write(subjectRefs);
    }

    /** {@link Iterable} overload of {@link #to(String...)}. */
    public GrantCompletion to(Iterable<String> subjectRefs) {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String ref : subjectRefs) list.add(ref);
        if (list.isEmpty()) return GrantCompletion.of(new GrantResult(null, 0));
        return write(list.toArray(String[]::new));
    }

    // ════════════════════════════════════════════════════════════════
    //  Internals
    // ════════════════════════════════════════════════════════════════

    private GrantCompletion write(String[] refs) {
        // Client-side schema validation was removed with the SchemaCache in
        // ADR 2026-04-18. Invalid subject types now fail at the SpiceDB
        // boundary as AuthxInvalidArgumentException.
        //
        // Route through GrantAction so caveat + expiration propagate into
        // the RelationshipUpdate written by the transport. Aggregate the
        // per-RPC GrantResults into a single result (SR:req-5):
        //   zedToken = token from the LAST internal write (SpiceDB's
        //              monotonically-increasing revision, so the latest
        //              covers all prior writes for consistency purposes)
        //   count    = SUM of counts across all internal writes
        String lastToken = null;
        int totalCount = 0;
        for (String id : ids) {
            for (R rel : relations) {
                var action = factory.resource(id).grant(rel.relationName());
                if (caveatName != null) {
                    action.withCaveat(caveatName, caveatContext);
                }
                if (expiresAt != null) {
                    action.expiresAt(expiresAt);
                }
                GrantResult r = action.to(refs);
                if (r.zedToken() != null) lastToken = r.zedToken();
                totalCount += r.count();
            }
        }
        return GrantCompletion.of(new GrantResult(lastToken, totalCount));
    }
}
