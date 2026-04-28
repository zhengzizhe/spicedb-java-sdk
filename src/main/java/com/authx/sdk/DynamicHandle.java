package com.authx.sdk;

import com.authx.sdk.action.RelationQuery;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.ExpandTree;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;

/**
 * String-based handle returned by {@link DynamicResourceEntry#select(String...)}.
 */
public final class DynamicHandle {

    private final ResourceFactory factory;
    private final String[] ids;

    DynamicHandle(ResourceFactory factory, String[] ids) {
        this.factory = factory;
        this.ids = ids;
    }

    public WriteFlow grant(String relation) {
        requireSingleId("grant");
        return newFlow().grant(relation);
    }

    public WriteFlow revoke(String relation) {
        requireSingleId("revoke");
        return newFlow().revoke(relation);
    }

    public TypedCheckAction check(String permission) {
        return new TypedCheckAction(factory, ids, new String[]{permission});
    }

    public TypedCheckAction check(String... permissions) {
        return new TypedCheckAction(factory, ids, permissions);
    }

    public TypedWhoQuery lookupSubjects(String subjectType, String permission) {
        requireSingleId("lookupSubjects");
        return new TypedWhoQuery(factory, ids[0], subjectType, permission);
    }

    public RelationQuery relations(String... relations) {
        requireSingleId("relations");
        return new RelationQuery(factory.resourceType(), ids[0], factory.transport(), relations);
    }

    public ExpandTree expand(String permission) {
        requireSingleId("expand");
        return factory.transport().expand(
                ResourceRef.of(factory.resourceType(), ids[0]),
                Permission.of(permission),
                Consistency.full());
    }

    private WriteFlow newFlow() {
        return new WriteFlow(factory.resourceType(), ids[0],
                factory.transport(), factory.schemaCache());
    }

    private void requireSingleId(String method) {
        if (ids.length != 1) {
            throw new IllegalStateException(
                    method + "() requires exactly one selected resource id; got "
                            + ids.length + " — use batch APIs for multi-resource writes");
        }
    }
}
