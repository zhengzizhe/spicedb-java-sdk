package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;

/**
 * Type-safe resource factory. Generated subclasses lock R and P to specific enums
 * so you can't accidentally pass a Folder.Rel where Document.Rel is expected.
 *
 * <pre>
 * DocumentResource doc = client.document();
 * doc.grant("doc-1", Document.Rel.EDITOR).toUser("bob");       // ✓
 * doc.grant("doc-1", Folder.Rel.EDITOR).toUser("bob");         // ✗ compile error
 * doc.check("doc-1", Document.Perm.VIEW, "alice");              // ✓
 * doc.grant("doc-1", "custom_rel", "bob");                      // ✓ string escape hatch
 * </pre>
 *
 * @param <R> Relation enum type (e.g., Document.Rel)
 * @param <P> Permission enum type (e.g., Document.Perm)
 */
public class TypedResourceFactory<R extends Relation.Named, P extends Permission.Named>
        extends ResourceFactory {

    protected TypedResourceFactory() {
        super();
    }

    /** Initialize from client. Used by codegen-generated subclasses. */
    protected void init(String resourceType, AuthxClient client) {
        init(resourceType, client.transport(), client.defaultSubjectType(), client.asyncExecutor());
    }

    // check(String, Permission.Named, String) is inherited from ResourceFactory — works with P directly.

    // ---- Typed grant (returns chain action — subclass generates to* methods) ----

    public TypedGrantAction<R> grant(String id, R relation) {
        return new TypedGrantAction<>(this, id, relation);
    }

    // ---- Typed revoke ----

    public TypedRevokeAction<R> revoke(String id, R relation) {
        return new TypedRevokeAction<>(this, id, relation);
    }
}
