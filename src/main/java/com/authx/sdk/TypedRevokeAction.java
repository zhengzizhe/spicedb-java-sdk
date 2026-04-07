package com.authx.sdk;

import com.authx.sdk.model.Relation;

/**
 * Typed revoke action. Iterates over ids × relations × subjects.
 * Codegen subclasses add fromUser(), fromFolder(), fromUserAll(), etc.
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

    /** String escape hatch — varargs. */
    public void from(String... subjectRefs) {
        for (String id : ids)
            for (R rel : relations)
                factory.revokeFromSubjects(id, rel.relationName(), subjectRefs);
    }

    /** String escape hatch — Collection. */
    public void from(java.util.Collection<String> subjectRefs) {
        from(subjectRefs.toArray(String[]::new));
    }
}
