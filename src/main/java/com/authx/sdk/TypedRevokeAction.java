package com.authx.sdk;

import com.authx.sdk.model.Relation;

/**
 * Base typed revoke action. Codegen subclasses add from*() methods per subject type.
 */
public class TypedRevokeAction<R extends Relation.Named> {

    protected final ResourceFactory factory;
    protected final String id;
    protected final R relation;

    public TypedRevokeAction(ResourceFactory factory, String id, R relation) {
        this.factory = factory;
        this.id = id;
        this.relation = relation;
    }

    /** String escape hatch — pass raw subject refs. */
    public void from(String... subjectRefs) {
        factory.revokeFromSubjects(id, relation.relationName(), subjectRefs);
    }
}
