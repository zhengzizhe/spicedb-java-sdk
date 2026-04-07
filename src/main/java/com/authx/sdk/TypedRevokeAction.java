package com.authx.sdk;

import com.authx.sdk.model.Relation;

/**
 * Base typed revoke action. Supports multiple relations.
 * Codegen subclasses add fromUser(), fromFolder(), fromUserAll(), etc.
 */
public class TypedRevokeAction<R extends Relation.Named> {

    protected final ResourceFactory factory;
    protected final String id;
    protected final R[] relations;

    @SafeVarargs
    public TypedRevokeAction(ResourceFactory factory, String id, R... relations) {
        this.factory = factory;
        this.id = id;
        this.relations = relations;
    }

    /** String escape hatch — pass raw subject refs. */
    public void from(String... subjectRefs) {
        for (R rel : relations) {
            factory.revokeFromSubjects(id, rel.relationName(), subjectRefs);
        }
    }
}
