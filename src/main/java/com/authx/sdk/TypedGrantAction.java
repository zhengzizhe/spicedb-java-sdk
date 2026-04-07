package com.authx.sdk;

import com.authx.sdk.model.Relation;

/**
 * Typed grant action. Iterates over ids × relations × subjects.
 * Codegen subclasses add toUser(), toFolder(), toUserAll(), etc.
 */
public class TypedGrantAction<R extends Relation.Named> {

    protected final ResourceFactory factory;
    protected final String[] ids;
    protected final R[] relations;

    @SafeVarargs
    public TypedGrantAction(ResourceFactory factory, String[] ids, R... relations) {
        this.factory = factory;
        this.ids = ids;
        this.relations = relations;
    }

    /** String escape hatch. */
    public void to(String... subjectRefs) {
        for (String id : ids)
            for (R rel : relations)
                factory.grantToSubjects(id, rel.relationName(), subjectRefs);
    }
}
