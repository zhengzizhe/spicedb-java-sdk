package com.authx.sdk;

import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.Relation;

/**
 * Base typed grant action. Codegen subclasses add to*() methods per subject type.
 *
 * <pre>
 * // String escape hatch (always available)
 * doc.grant("doc-1", Document.Rel.EDITOR).to("user:bob");
 * </pre>
 */
public class TypedGrantAction<R extends Relation.Named> {

    protected final ResourceFactory factory;
    protected final String id;
    protected final R relation;

    public TypedGrantAction(ResourceFactory factory, String id, R relation) {
        this.factory = factory;
        this.id = id;
        this.relation = relation;
    }

    /** String escape hatch — pass raw subject refs. */
    public GrantResult to(String... subjectRefs) {
        factory.grantToSubjects(id, relation.relationName(), subjectRefs);
        return null; // TODO: return actual result
    }
}
