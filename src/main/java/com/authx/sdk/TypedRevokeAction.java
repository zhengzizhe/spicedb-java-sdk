package com.authx.sdk;

import com.authx.sdk.action.RevokeCompletion;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.model.SubjectRef;

import java.util.Collection;

/**
 * Typed revoke action — mirror of {@link TypedGrantAction} for removing
 * relationships.
 *
 * <p><b>No-match behaviour:</b> SpiceDB tolerates revokes that match no
 * tuples — a revoke with a wrong subject type silently succeeds as a
 * no-op rather than raising an error. Client code that wants fast-fail
 * semantics must check the {@link com.authx.sdk.model.RevokeResult}'s
 * {@code count()} or issue a {@code check()} first. (Pre-ADR 2026-04-18
 * the SDK caught subject-type mismatches locally via SchemaCache; that
 * check was removed with the cache subsystem.)
 *
 * <pre>
 * doc.select("doc-1").revoke(Document.Rel.EDITOR).fromUser("bob");
 * doc.select("doc-1").revoke(Document.Rel.FOLDER).fromFolder("f-1");
 * task.select("t-1").revoke(Task.Rel.DOCUMENT)
 *     .from(SubjectRef.of("document", "doc-5", null));
 * </pre>
 */
public class TypedRevokeAction<R extends Relation.Named> {

    protected final ResourceFactory factory;
    protected final String[] ids;
    protected final R[] relations;

    @SafeVarargs
    public TypedRevokeAction(ResourceFactory factory, String[] ids, R... relations) {
        this.factory = factory;
        this.ids = ids;
        this.relations = relations;
    }

    // ════════════════════════════════════════════════════════════════
    //  Terminal methods — subjects come in as SubjectRef or canonical strings
    // ════════════════════════════════════════════════════════════════

    /** Revoke from one or more {@link SubjectRef subjects}. */
    public RevokeCompletion from(SubjectRef... subjects) {
        if (subjects == null || subjects.length == 0) {
            return RevokeCompletion.of(new RevokeResult(null, 0));
        }
        String[] refs = new String[subjects.length];
        for (int i = 0; i < subjects.length; i++) {
            refs[i] = subjects[i].toRefString();
        }
        return write(refs);
    }

    /** Collection overload of {@link #from(SubjectRef...)}. */
    public RevokeCompletion from(Collection<SubjectRef> subjects) {
        return from(subjects.toArray(SubjectRef[]::new));
    }

    /**
     * Revoke from one or more canonical subject strings:
     * {@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}.
     */
    public RevokeCompletion from(String... subjectRefs) {
        if (subjectRefs == null || subjectRefs.length == 0) {
            return RevokeCompletion.of(new RevokeResult(null, 0));
        }
        return write(subjectRefs);
    }

    // ════════════════════════════════════════════════════════════════
    //  Internals
    // ════════════════════════════════════════════════════════════════

    private RevokeCompletion write(String[] refs) {
        // Client-side schema validation was removed with the SchemaCache in
        // ADR 2026-04-18. Invalid subject types now fail at the SpiceDB
        // boundary as AuthxInvalidArgumentException.
        //
        // Aggregate per-RPC RevokeResults into a single result (SR:req-6).
        String lastToken = null;
        int totalCount = 0;
        for (String id : ids) {
            for (R rel : relations) {
                RevokeResult r = factory.revoke(id, rel.relationName(), refs);
                if (r.zedToken() != null) lastToken = r.zedToken();
                totalCount += r.count();
            }
        }
        return RevokeCompletion.of(new RevokeResult(lastToken, totalCount));
    }
}
