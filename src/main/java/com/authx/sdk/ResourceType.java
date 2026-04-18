package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;

import java.util.Objects;

/**
 * Typed descriptor for a SpiceDB resource type, carrying the canonical
 * type name plus the {@link Relation.Named} and {@link Permission.Named}
 * enum classes declared on that type. Declared (hand-written or from
 * external codegen) per-type and exposed as {@code Xxx.TYPE}:
 *
 * <pre>
 * public static final ResourceType&lt;Document.Rel, Document.Perm&gt; TYPE =
 *     ResourceType.of("document", Document.Rel.class, Document.Perm.class);
 * </pre>
 *
 * <p>Used as the token the business code hands to {@link AuthxClient#on(ResourceType)}
 * to get a fully typed chain entry:
 *
 * <pre>
 * client.on(Document.TYPE)
 *       .select(docId)
 *       .check(Document.Perm.VIEW)
 *       .by(userId);
 * </pre>
 *
 * <p>Implements {@link CharSequence} and overrides {@link #toString()} so
 * it also slots into any string-based API (e.g. log lines, matrix keys)
 * as the bare type name.
 */
public final class ResourceType<R extends Enum<R> & Relation.Named,
                                P extends Enum<P> & Permission.Named> {

    private final String name;
    private final Class<R> relClass;
    private final Class<P> permClass;

    private ResourceType(String name, Class<R> relClass, Class<P> permClass) {
        this.name = Objects.requireNonNull(name, "name");
        this.relClass = relClass;
        this.permClass = permClass;
    }

    public static <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    ResourceType<R, P> of(String name, Class<R> relClass, Class<P> permClass) {
        return new ResourceType<>(name, relClass, permClass);
    }

    /** The canonical type name as declared in the SpiceDB schema. */
    public String name() { return name; }

    /** The relation enum class — drives {@code EnumSet} / reflective iteration. */
    public Class<R> relClass() { return relClass; }

    /** The permission enum class — used by {@code checkAll()} to enumerate every permission. */
    public Class<P> permClass() { return permClass; }

    @Override public String toString() { return name; }

    @Override public boolean equals(Object o) {
        return o instanceof ResourceType<?, ?> rt && name.equals(rt.name);
    }

    @Override public int hashCode() { return name.hashCode(); }
}
