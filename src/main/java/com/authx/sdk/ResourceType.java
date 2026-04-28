package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Typed descriptor for a SpiceDB resource type, carrying the canonical
 * type name plus the {@link Relation.Named} and {@link Permission.Named}
 * enum classes declared on that type.
 *
 * <p>Normally obtained from a generated resource class:
 *
 * <pre>
 * import static com.example.schema.Document.Document;
 * import static com.example.schema.User.User;
 *
 * client.on(Document).select(id).check(Document.Perm.VIEW).by(User, userId);
 * </pre>
 *
 * <p>Generated resource classes create descriptors through
 * {@link #of(String, Supplier, Supplier)}. End users usually do not
 * instantiate this class directly — regenerate your schema classes via
 * AuthxCodegen instead.
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

    protected ResourceType(String name, Supplier<R[]> relValues, Supplier<P[]> permValues) {
        this(name, enumClass(relValues, "relValues"), enumClass(permValues, "permValues"));
    }

    public static <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    ResourceType<R, P> of(String name, Class<R> relClass, Class<P> permClass) {
        return new ResourceType<>(name, relClass, permClass);
    }

    public static <R extends Enum<R> & Relation.Named, P extends Enum<P> & Permission.Named>
    ResourceType<R, P> of(String name, Supplier<R[]> relValues, Supplier<P[]> permValues) {
        return new ResourceType<>(
                name,
                enumClass(relValues, "relValues"),
                enumClass(permValues, "permValues"));
    }

    @SuppressWarnings("unchecked")
    protected static <E extends Enum<E>> Class<E> enumClass(Supplier<E[]> values, String name) {
        E[] enums = Objects.requireNonNull(values, name).get();
        Objects.requireNonNull(enums, name + ".get()");
        return (Class<E>) enums.getClass().getComponentType();
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
