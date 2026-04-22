package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;

import java.util.Objects;

/**
 * Typed descriptor for a SpiceDB resource type, carrying the canonical
 * type name plus the {@link Relation.Named} and {@link Permission.Named}
 * enum classes declared on that type.
 *
 * <p>Normally obtained from a generated {@code Schema} aggregator:
 *
 * <pre>
 * import static com.authx.testapp.schema.Schema.*;
 * client.on(Document).select(id).check(Document.Perm.VIEW).by(User, userId);
 * </pre>
 *
 * <p>This class is open for subclassing <em>only</em> so AuthxCodegen can
 * emit per-resource-type descriptor subclasses with typed {@code Rel} /
 * {@code Perm} proxy fields attached. End users should not subclass
 * directly — regenerate your {@code Schema.java} via AuthxCodegen instead.
 *
 * <p>Instances implement value equality on {@link #name()} only — two
 * descriptors with the same type name are equal regardless of subclass
 * identity. {@link #toString()} returns the bare name so instances slot
 * into string-based APIs (log lines, matrix keys) transparently.
 */
public class ResourceType<R extends Enum<R> & Relation.Named,
                          P extends Enum<P> & Permission.Named> {

    private final String name;
    private final Class<R> relClass;
    private final Class<P> permClass;

    protected ResourceType(String name, Class<R> relClass, Class<P> permClass) {
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
