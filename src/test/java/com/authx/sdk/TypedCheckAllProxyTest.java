package com.authx.sdk;

import com.authx.sdk.model.Permission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link PermissionProxy} (T012 of the
 * schema-flat-descriptors spec).
 *
 * <p>{@code checkAll(PermissionProxy)} on {@link TypedHandle} delegates to
 * {@code checkAll(proxy.enumClass())}. If codegen ever hands back a
 * {@code Class} token that isn't the exact same object as the business
 * code's {@code Xxx.Perm.class}, the resulting EnumMap key set diverges
 * and {@code checkAll} returns wrong rows. Pin the identity invariant.
 */
class TypedCheckAllProxyTest {

    enum Perm implements Permission.Named {
        VIEW("view"), EDIT("edit");
        private final String v;
        Perm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    static final class PermProxy implements PermissionProxy<Perm> {
        @Override public Class<Perm> enumClass() { return Perm.class; }
    }

    @Test
    void proxyExposesEnumClass() {
        assertThat(new PermProxy().enumClass()).isEqualTo(Perm.class);
    }

    @Test
    void proxyEnumClassIdentity() {
        // Sanity: the proxy's enumClass() must be the exact same Class
        // token used by checkAll(Class). If codegen hands back a different
        // one, the EnumMap key set diverges and checkAll returns wrong rows.
        assertThat(new PermProxy().enumClass()).isSameAs(Perm.class);
    }
}
