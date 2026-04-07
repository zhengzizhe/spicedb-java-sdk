package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Type-safe resource factory with N×M×K matrix support.
 *
 * <pre>
 * // Single
 * doc.select("doc-1").grant(Document.Rel.EDITOR).toUser("bob");
 * doc.select("doc-1").check(Document.Perm.VIEW).by("alice");
 *
 * // Matrix: 3 docs × 2 rels × 2 users = 12 operations
 * doc.select("doc-1", "doc-2", "doc-3")
 *    .grant(Document.Rel.EDITOR, Document.Rel.COMMENTER)
 *    .toUser("bob", "alice");
 *
 * // Check matrix: 2 docs × 3 perms × 2 users
 * doc.select("doc-1", "doc-2")
 *    .check(Document.Perm.VIEW, Document.Perm.EDIT, Document.Perm.DELETE)
 *    .by("alice", "bob");
 * // → Map&lt;id, Map&lt;perm, Map&lt;user, Boolean&gt;&gt;&gt;
 * </pre>
 */
public class TypedResourceFactory<R extends Relation.Named, P extends Permission.Named>
        extends ResourceFactory {

    protected TypedResourceFactory() {
        super();
    }

    protected void init(String resourceType, AuthxClient client) {
        init(resourceType, client.transport(), client.defaultSubjectType(), client.asyncExecutor());
    }

    // ---- Select: single or multiple ids ----

    @SuppressWarnings("unchecked")
    public TypedHandle<R, P> select(String... ids) {
        return new TypedHandle<>(this, ids);
    }

    public TypedHandle<R, P> select(Collection<String> ids) {
        return new TypedHandle<>(this, ids.toArray(String[]::new));
    }

    // ---- Handle: grant / revoke / check ----

    public static class TypedHandle<R extends Relation.Named, P extends Permission.Named> {
        protected final ResourceFactory factory;
        protected final String[] ids;

        public TypedHandle(ResourceFactory factory, String[] ids) {
            this.factory = factory;
            this.ids = ids;
        }

        @SuppressWarnings("unchecked")
        public TypedGrantAction<R> grant(R... relations) {
            return new TypedGrantAction<>(factory, ids, relations);
        }

        @SuppressWarnings("unchecked")
        public TypedRevokeAction<R> revoke(R... relations) {
            return new TypedRevokeAction<>(factory, ids, relations);
        }

        /** Single permission check. */
        public TypedCheckAction check(P permission) {
            return new TypedCheckAction(factory, ids, new String[]{permission.permissionName()});
        }

        /** Multi-permission check (matrix). */
        @SafeVarargs
        public final TypedCheckAction check(P... permissions) {
            String[] perms = new String[permissions.length];
            for (int i = 0; i < permissions.length; i++) perms[i] = permissions[i].permissionName();
            return new TypedCheckAction(factory, ids, perms);
        }
    }

    // ---- Check action: by() returns single boolean or matrix ----

    public static class TypedCheckAction {
        private final ResourceFactory factory;
        private final String[] ids;
        private final String[] permissions;

        public TypedCheckAction(ResourceFactory factory, String[] ids, String[] permissions) {
            this.factory = factory;
            this.ids = ids;
            this.permissions = permissions;
        }

        /** Single user check. Returns boolean (single id × single perm) or throws if matrix. */
        public boolean by(String userId) {
            if (ids.length == 1 && permissions.length == 1) {
                return factory.check(ids[0], permissions[0], userId);
            }
            throw new IllegalStateException(
                    "Use byAll() for matrix check (" + ids.length + " ids × " + permissions.length + " perms)");
        }

        /** Multi-user / matrix check. Returns nested map: id → perm → user → allowed. */
        public Map<String, Map<String, Map<String, Boolean>>> by(String... userIds) {
            if (userIds.length == 1 && ids.length == 1 && permissions.length == 1) {
                // Degenerate case: wrap single result
                return Map.of(ids[0], Map.of(permissions[0], Map.of(userIds[0],
                        factory.check(ids[0], permissions[0], userIds[0]))));
            }
            Map<String, Map<String, Map<String, Boolean>>> result = new LinkedHashMap<>();
            for (String id : ids) {
                Map<String, Map<String, Boolean>> permMap = new LinkedHashMap<>();
                for (String perm : permissions) {
                    Map<String, Boolean> userMap = new LinkedHashMap<>();
                    for (String user : userIds) {
                        userMap.put(user, factory.check(id, perm, user));
                    }
                    permMap.put(perm, userMap);
                }
                result.put(id, permMap);
            }
            return result;
        }
    }
}
