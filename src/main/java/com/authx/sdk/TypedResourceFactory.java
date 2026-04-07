package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;

/**
 * Type-safe resource factory. Generated subclasses lock R and P to specific enums.
 *
 * <pre>
 * DocumentResource doc = new DocumentResource(client);
 * doc.on("doc-1").grant(Document.Rel.EDITOR).toUser("bob");
 * doc.on("doc-1").grant(Document.Rel.EDITOR, Document.Rel.COMMENTER).toUser("bob");
 * doc.on("doc-1").check(Document.Perm.VIEW).by("alice");
 * doc.on("doc-1").revoke(Document.Rel.EDITOR).fromUser("bob");
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

    protected void init(String resourceType, AuthxClient client) {
        init(resourceType, client.transport(), client.defaultSubjectType(), client.asyncExecutor());
    }

    /**
     * Start a chain on a specific resource id.
     * Subclasses override to return typed handle with grant/check/revoke.
     */
    public TypedHandle<R, P> select(String id) {
        return new TypedHandle<>(this, id);
    }

    /** Typed handle — the middle of the chain. Codegen subclasses override grant/revoke return types. */
    public static class TypedHandle<R extends Relation.Named, P extends Permission.Named> {
        protected final ResourceFactory factory;
        protected final String id;

        public TypedHandle(ResourceFactory factory, String id) {
            this.factory = factory;
            this.id = id;
        }

        @SuppressWarnings("unchecked")
        public TypedGrantAction<R> grant(R... relations) {
            return new TypedGrantAction<>(factory, id, relations);
        }

        @SuppressWarnings("unchecked")
        public TypedRevokeAction<R> revoke(R... relations) {
            return new TypedRevokeAction<>(factory, id, relations);
        }

        public TypedCheckAction check(P permission) {
            return new TypedCheckAction(factory, id, permission.permissionName());
        }
    }

    /** Check chain terminal — .by("alice") */
    public static class TypedCheckAction {
        private final ResourceFactory factory;
        private final String id;
        private final String permission;

        public TypedCheckAction(ResourceFactory factory, String id, String permission) {
            this.factory = factory;
            this.id = id;
            this.permission = permission;
        }

        public boolean by(String userId) {
            return factory.check(id, permission, userId);
        }
    }
}
