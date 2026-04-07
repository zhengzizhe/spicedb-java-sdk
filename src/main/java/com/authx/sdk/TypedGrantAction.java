package com.authx.sdk;

import com.authx.sdk.model.Relation;

/**
 * Base typed grant action. Supports multiple relations.
 * Codegen subclasses add toUser(), toFolder(), toUserAll(), etc.
 *
 * <pre>
 * doc.on("doc-1").grant(Document.Rel.EDITOR, Document.Rel.COMMENTER).toUser("bob");
 * </pre>
 */
public class TypedGrantAction<R extends Relation.Named> {

    protected final ResourceFactory factory;
    protected final String id;
    protected final R[] relations;

    @SafeVarargs
    public TypedGrantAction(ResourceFactory factory, String id, R... relations) {
        this.factory = factory;
        this.id = id;
        this.relations = relations;
    }

    /** String escape hatch — pass raw subject refs. */
    public void to(String... subjectRefs) {
        for (R rel : relations) {
            factory.grantToSubjects(id, rel.relationName(), subjectRefs);
        }
    }
}
