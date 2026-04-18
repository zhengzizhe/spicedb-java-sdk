package com.authx.sdk;

import com.authx.sdk.action.RevokeCompletion;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.model.SubjectRef;

import java.util.Collection;

/**
 * Typed revoke action — mirror of {@link TypedGrantAction} for removing
 * relationships. Subject-type methods validate against the schema at call
 * time so revoking from a subject type the relation doesn't accept raises
 * a clear {@link IllegalArgumentException} up-front rather than silently
 * succeeding as a no-op revoke (SpiceDB tolerates revokes that match no
 * tuples, which can hide programming errors).
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
    //  Common subject-type shortcuts (validated against schema)
    // ════════════════════════════════════════════════════════════════

    public RevokeCompletion fromUser(String... userIds) {
        return writeTypedSubjects("user", null, userIds);
    }

    public RevokeCompletion fromUser(Collection<String> userIds) {
        return fromUser(userIds.toArray(String[]::new));
    }

    public RevokeCompletion fromGroupMember(String... groupIds) {
        return writeTypedSubjects("group", "member", groupIds);
    }

    public RevokeCompletion fromGroupMember(Collection<String> groupIds) {
        return fromGroupMember(groupIds.toArray(String[]::new));
    }

    public RevokeCompletion fromUserAll() {
        return write(new String[]{"user:*"});
    }

    // ════════════════════════════════════════════════════════════════
    //  Generic subject entry — supports any subject type in the schema
    // ════════════════════════════════════════════════════════════════

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

    public RevokeCompletion from(Collection<SubjectRef> subjects) {
        return from(subjects.toArray(SubjectRef[]::new));
    }

    /** Raw-string escape hatch — still validated against schema at runtime. */
    public RevokeCompletion fromSubjectRefs(String... subjectRefs) {
        if (subjectRefs == null || subjectRefs.length == 0) {
            return RevokeCompletion.of(new RevokeResult(null, 0));
        }
        return write(subjectRefs);
    }

    /** Collection overload for {@link #fromSubjectRefs(String...)}. */
    public RevokeCompletion fromSubjectRefs(Collection<String> subjectRefs) {
        if (subjectRefs == null || subjectRefs.isEmpty()) {
            return RevokeCompletion.of(new RevokeResult(null, 0));
        }
        return write(subjectRefs.toArray(String[]::new));
    }

    // ════════════════════════════════════════════════════════════════
    //  Internals
    // ════════════════════════════════════════════════════════════════

    private RevokeCompletion writeTypedSubjects(String type, String subRelation, String[] ids) {
        if (ids == null || ids.length == 0) {
            return RevokeCompletion.of(new RevokeResult(null, 0));
        }
        String[] refs = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            refs[i] = (subRelation == null || subRelation.isEmpty())
                    ? type + ":" + ids[i]
                    : type + ":" + ids[i] + "#" + subRelation;
        }
        return write(refs);
    }

    private RevokeCompletion write(String[] refs) {
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
        // Aggregate per-RPC RevokeResults into a single result (SR:req-6).
        String lastToken = null;
        int totalCount = 0;
        for (String id : ids) {
            for (R rel : relations) {
                RevokeResult r = factory.revokeFromSubjects(id, rel.relationName(), refs);
                if (r.zedToken() != null) lastToken = r.zedToken();
                totalCount += r.count();
            }
        }
        return RevokeCompletion.of(new RevokeResult(lastToken, totalCount));
    }
}
