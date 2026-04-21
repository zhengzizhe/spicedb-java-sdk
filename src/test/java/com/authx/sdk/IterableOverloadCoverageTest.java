package com.authx.sdk;

import com.authx.sdk.action.CheckAction;
import com.authx.sdk.action.GrantAction;
import com.authx.sdk.action.RevokeAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for req-13: every action that takes subjects from
 * business code must expose a {@code (ResourceType, Iterable<String>)}
 * typed-batch overload so collections-held ids flow through without
 * conversion. This test is a reflection sweep — future actions that
 * bypass this pattern will fail here before they hit production.
 */
class IterableOverloadCoverageTest {

    @Test
    void grantActionHasIterableTypedTo() {
        assertThat(methodExists(GrantAction.class,
                "to", ResourceType.class, Iterable.class))
                .as("GrantAction.to(ResourceType, Iterable<String>)")
                .isTrue();
    }

    @Test
    void revokeActionHasIterableTypedFrom() {
        assertThat(methodExists(RevokeAction.class,
                "from", ResourceType.class, Iterable.class))
                .as("RevokeAction.from(ResourceType, Iterable<String>)")
                .isTrue();
    }

    @Test
    void checkActionHasIterableTypedByAll() {
        assertThat(methodExists(CheckAction.class,
                "byAll", ResourceType.class, Iterable.class))
                .as("CheckAction.byAll(ResourceType, Iterable<String>)")
                .isTrue();
    }

    @Test
    void typedCheckActionHasIterableTypedByAll() {
        assertThat(methodExists(TypedCheckAction.class,
                "byAll", ResourceType.class, Iterable.class))
                .as("TypedCheckAction.byAll(ResourceType, Iterable<String>)")
                .isTrue();
    }

    @Test
    void typedResourceEntryHasIterableTypedFindBy() {
        assertThat(methodExists(TypedResourceEntry.class,
                "findBy", ResourceType.class, Iterable.class))
                .as("TypedResourceEntry.findBy(ResourceType, Iterable<String>)")
                .isTrue();
    }

    // ---- Single-id typed overload coverage ----

    @Test
    void grantActionHasSingleIdTypedTo() {
        assertThat(methodExists(GrantAction.class,
                "to", ResourceType.class, String.class)).isTrue();
    }

    @Test
    void revokeActionHasSingleIdTypedFrom() {
        assertThat(methodExists(RevokeAction.class,
                "from", ResourceType.class, String.class)).isTrue();
    }

    @Test
    void checkActionHasSingleIdTypedBy() {
        assertThat(methodExists(CheckAction.class,
                "by", ResourceType.class, String.class)).isTrue();
    }

    @Test
    void lookupQueryHasSingleIdTypedBy() {
        assertThat(methodExists(LookupQuery.class,
                "by", ResourceType.class, String.class)).isTrue();
    }

    // ---- Wildcard typed overload coverage ----

    @Test
    void grantActionHasToWildcard() {
        assertThat(methodExists(GrantAction.class,
                "toWildcard", ResourceType.class)).isTrue();
    }

    @Test
    void revokeActionHasFromWildcard() {
        assertThat(methodExists(RevokeAction.class,
                "fromWildcard", ResourceType.class)).isTrue();
    }

    // ---- Typed chain mirrors (TypedGrantAction / TypedRevokeAction) ----
    // The typed chain is what business code enters via
    //   client.on(ResourceType).select(...).grant(Rel)
    // so every user-facing typed overload on the untyped action classes
    // must also exist here, or the typed chain goes stale.

    @Test
    void typedGrantActionHasTypedTo() {
        assertThat(methodExists(TypedGrantAction.class,
                "to", ResourceType.class, String.class)).isTrue();
    }

    @Test
    void typedGrantActionHasTypedToSubRelation() {
        assertThat(methodExists(TypedGrantAction.class,
                "to", ResourceType.class, String.class, String.class)).isTrue();
    }

    @Test
    void typedGrantActionHasTypedToWildcard() {
        assertThat(methodExists(TypedGrantAction.class,
                "toWildcard", ResourceType.class)).isTrue();
    }

    @Test
    void typedGrantActionHasTypedIterableTo() {
        assertThat(methodExists(TypedGrantAction.class,
                "to", ResourceType.class, Iterable.class)).isTrue();
    }

    @Test
    void typedGrantActionHasBareIdTo() {
        assertThat(methodExists(TypedGrantAction.class,
                "to", String.class)).isTrue();
    }

    @Test
    void typedRevokeActionHasTypedFrom() {
        assertThat(methodExists(TypedRevokeAction.class,
                "from", ResourceType.class, String.class)).isTrue();
    }

    @Test
    void typedRevokeActionHasTypedFromSubRelation() {
        assertThat(methodExists(TypedRevokeAction.class,
                "from", ResourceType.class, String.class, String.class)).isTrue();
    }

    @Test
    void typedRevokeActionHasTypedFromWildcard() {
        assertThat(methodExists(TypedRevokeAction.class,
                "fromWildcard", ResourceType.class)).isTrue();
    }

    @Test
    void typedRevokeActionHasTypedIterableFrom() {
        assertThat(methodExists(TypedRevokeAction.class,
                "from", ResourceType.class, Iterable.class)).isTrue();
    }

    @Test
    void typedRevokeActionHasBareIdFrom() {
        assertThat(methodExists(TypedRevokeAction.class,
                "from", String.class)).isTrue();
    }

    private boolean methodExists(Class<?> c, String name, Class<?>... paramTypes) {
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != paramTypes.length) continue;
            boolean ok = true;
            for (int i = 0; i < paramTypes.length; i++) {
                // Use exact match on the parameter type (not isAssignableFrom)
                // — Iterable.class must match the declared Iterable<String>,
                // not the more specific Collection<SubjectRef>.
                if (!paramTypes[i].equals(m.getParameterTypes()[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) return true;
        }
        return false;
    }
}
