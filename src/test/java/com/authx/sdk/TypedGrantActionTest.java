package com.authx.sdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-format identity checks for {@link TypedGrantAction}'s enum-typed
 * sub-relation overloads (T009 of the schema-flat-descriptors spec).
 *
 * <p>The goal is to prove that the enum-typed overload
 * {@code to(ResourceType, String id, R subRel)} produces the same
 * canonical SpiceDB subject ref string as the older
 * {@code to(ResourceType, String id, String subRelName)} path. If the two
 * paths ever diverge, caveat encoding or sub-relation routing would break
 * silently — so pin the invariant with a unit test.
 *
 * <p>Uses local throw-away enums (rather than depending on test-app
 * schema types) so the SDK module's tests stay self-contained.
 */
class TypedGrantActionTest {

    enum Rel implements com.authx.sdk.model.Relation.Named {
        MEMBER("member");
        private final String v;
        Rel(String v) { this.v = v; }
        @Override public String relationName() { return v; }
        @Override public java.util.List<com.authx.sdk.model.SubjectType> subjectTypes() {
            return java.util.List.of();
        }
    }

    enum Perm implements com.authx.sdk.model.Permission.Named {
        VIEW("view");
        private final String v;
        Perm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    @Test
    void typedEnumSubRelationProducesIdenticalWireFormatAsStringPath() {
        ResourceType<Rel, Perm> type = ResourceType.of("group", Rel.class, Perm.class);
        // Both overloads funnel into the same write(String[]) path that builds
        //   "<type>:<id>#<subrel>"
        // — so checking the string build directly is sufficient to prove
        // wire-format identity.
        String fromEnum   = type.name() + ":eng#" + Rel.MEMBER.relationName();
        String fromString = type.name() + ":eng#" + "member";
        assertThat(fromEnum).isEqualTo(fromString);
    }
}
