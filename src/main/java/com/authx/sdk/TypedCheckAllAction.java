package com.authx.sdk;

import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed "check every permission declared on the type" terminator.
 * Reads every enum constant of the bound {@code Perm} class, sends them
 * as a single {@code CheckBulkPermissions} RPC, and returns the results
 * as a typed {@link EnumMap}. Saves business code from listing every
 * {@code Perm.X} constant manually and from picking values out of a
 * {@link com.authx.sdk.model.CheckMatrix} by string name afterwards.
 *
 * <p>Construct via {@link TypedHandle#checkAll(Class)}.
 *
 * <pre>
 * EnumMap&lt;Document.Perm, Boolean&gt; toolbar =
 *     Document.select(client, docId).checkAll(Document.Perm.class).by(userId);
 *
 * if (toolbar.get(Document.Perm.EDIT)) showEditButton();
 * </pre>
 */
public class TypedCheckAllAction<E extends Enum<E> & Permission.Named> {

    private final ResourceFactory factory;
    private final String[] ids;
    private final Class<E> permClass;
    private Consistency consistency = Consistency.minimizeLatency();
    private Map<String, Object> context;

    public TypedCheckAllAction(ResourceFactory factory, String[] ids, Class<E> permClass) {
        this.factory = factory;
        this.ids = ids;
        this.permClass = permClass;
    }

    public TypedCheckAllAction<E> withConsistency(Consistency c) { this.consistency = c; return this; }
    public TypedCheckAllAction<E> withContext(Map<String, Object> ctx) { this.context = ctx; return this; }

    /** Caveat context from alternating key-value pairs. */
    public TypedCheckAllAction<E> withContext(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must have even length");
        }
        var map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (!(keyValues[i] instanceof String key)) {
                throw new IllegalArgumentException("Key at index " + i + " must be a String");
            }
            map.put(key, keyValues[i + 1]);
        }
        this.context = map;
        return this;
    }

    /** Alias for {@link #withContext(Map)}. */
    public TypedCheckAllAction<E> given(Map<String, Object> ctx) { return withContext(ctx); }

    /** Alias for {@link #withContext(Object...)}. */
    public TypedCheckAllAction<E> given(Object... keyValues) { return withContext(keyValues); }

    // ────────────────────────────────────────────────────────────────
    //  Single-resource terminators
    // ────────────────────────────────────────────────────────────────

    /**
     * Resolve every permission against a single user id and return a typed
     * {@link EnumMap}. Requires exactly one selected resource id — for the
     * multi-resource case use {@link #byAll(String)}.
     */
    public EnumMap<E, Boolean> by(String userId) {
        if (ids.length != 1) {
            throw new IllegalStateException(
                    "checkAll(...).by(String) requires exactly one resource id; "
                            + "use byAll(userId) or loop for multi-resource");
        }
        return by(SubjectRef.of(factory.defaultSubjectType(), userId, null));
    }

    public EnumMap<E, Boolean> by(SubjectRef subject) {
        if (ids.length != 1) {
            throw new IllegalStateException(
                    "checkAll(...).by(SubjectRef) requires exactly one resource id");
        }
        E[] values = permClass.getEnumConstants();
        var result = new EnumMap<E, Boolean>(permClass);
        var items = new ArrayList<SdkTransport.BulkCheckItem>(values.length);
        String resourceType = factory.resourceType();
        for (E v : values) {
            items.add(new SdkTransport.BulkCheckItem(
                    ResourceRef.of(resourceType, ids[0]),
                    Permission.of(v.permissionName()),
                    subject));
        }
        List<CheckResult> results = factory.transport().checkBulkMulti(items, consistency);
        for (int i = 0; i < values.length; i++) {
            result.put(values[i], i < results.size() && results.get(i).hasPermission());
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────
    //  Multi-resource terminators
    // ────────────────────────────────────────────────────────────────

    /**
     * Resolve every permission across every selected resource id in one
     * bulk RPC. Returns {@code Map<resourceId, EnumMap<Perm, Boolean>>}.
     *
     * <pre>
     * Map&lt;String, EnumMap&lt;Document.Perm, Boolean&gt;&gt; perRow =
     *     Document.select(client, pageIds)
     *         .checkAll(Document.Perm.class).byAll(userId);
     * </pre>
     */
    public Map<String, EnumMap<E, Boolean>> byAll(String userId) {
        return byAll(SubjectRef.of(factory.defaultSubjectType(), userId, null));
    }

    public Map<String, EnumMap<E, Boolean>> byAll(SubjectRef subject) {
        E[] values = permClass.getEnumConstants();
        String resourceType = factory.resourceType();
        var items = new ArrayList<SdkTransport.BulkCheckItem>(ids.length * values.length);
        for (String id : ids) {
            for (E v : values) {
                items.add(new SdkTransport.BulkCheckItem(
                        ResourceRef.of(resourceType, id),
                        Permission.of(v.permissionName()),
                        subject));
            }
        }
        List<CheckResult> results = factory.transport().checkBulkMulti(items, consistency);
        var out = new LinkedHashMap<String, EnumMap<E, Boolean>>();
        int idx = 0;
        for (String id : ids) {
            var permMap = new EnumMap<E, Boolean>(permClass);
            for (E v : values) {
                permMap.put(v, idx < results.size() && results.get(idx).hasPermission());
                idx++;
            }
            out.put(id, permMap);
        }
        return out;
    }
}
