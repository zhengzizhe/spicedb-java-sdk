package com.authx.sdk;

import com.authx.sdk.model.Permission;

/**
 * Marker interface for generated {@code XxxPermProxy} classes. The single
 * {@link #enumClass()} method lets the SDK's {@code checkAll(...)} overloads
 * recover the underlying permission enum class from a proxy instance, so
 * business code can write
 *
 * <pre>
 * client.on(Document).select(id).checkAll(Document.Perm).by(User, userId);
 * </pre>
 *
 * where {@code Document.Perm} is a generated proxy with exposed fields
 * (e.g. {@code Document.Perm.VIEW}) and this method backs the
 * {@code checkAll(...)} dispatch.
 *
 * <p>Implemented only by AuthxCodegen output. End users should not
 * implement this interface directly.
 *
 * @apiNote The {@link #enumClass()} method is public because interfaces
 *          require it, but business code should pass the proxy directly
 *          to {@code checkAll(...)} rather than calling {@code enumClass()}
 *          itself. If you need the enum class, write
 *          {@code Document.Perm.class} at the enum container directly.
 */
public interface PermissionProxy<P extends Enum<P> & Permission.Named> {

    /** The permission enum class backing this proxy. */
    Class<P> enumClass();
}
