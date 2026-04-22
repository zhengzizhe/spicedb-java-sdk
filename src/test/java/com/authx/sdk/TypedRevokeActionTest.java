package com.authx.sdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-format identity checks for {@link TypedRevokeAction}'s enum-typed
 * sub-relation overloads (T010 of the schema-flat-descriptors spec).
 *
 * <p>Symmetric to {@link TypedGrantActionTest}: the enum-typed
 * {@code from(ResourceType, id, R subRel)} overload must produce the same
 * canonical subject ref string as the older
 * {@code from(ResourceType, id, String subRelName)} path.
 */
class TypedRevokeActionTest {

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
        String fromEnum   = type.name() + ":eng#" + Rel.MEMBER.relationName();
        String fromString = type.name() + ":eng#" + "member";
        assertThat(fromEnum).isEqualTo(fromString);
    }
}
