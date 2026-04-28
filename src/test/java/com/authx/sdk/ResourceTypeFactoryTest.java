package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceTypeFactoryTest {

    enum TestRel implements Relation.Named {
        OWNER("owner", "user"),
        MEMBER("member", "user");

        private final String value;
        private final List<SubjectType> subjectTypes;
        TestRel(String v, String... sts) {
            this.value = v;
            this.subjectTypes = Arrays.stream(sts).map(SubjectType::parse).toList();
        }
        @Override public String relationName() { return value; }
        @Override public List<SubjectType> subjectTypes() { return subjectTypes; }
    }

    enum TestPerm implements Permission.Named {
        VIEW("view"), EDIT("edit");
        private final String v;
        TestPerm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    @Test
    void factoryRetainsMetadata() {
        ResourceType<TestRel, TestPerm> desc = ResourceType.of("test", TestRel.class, TestPerm.class);
        assertThat(desc.name()).isEqualTo("test");
        assertThat(desc.relClass()).isEqualTo(TestRel.class);
        assertThat(desc.permClass()).isEqualTo(TestPerm.class);
    }

    @Test
    void descriptorsWithSameNameAreEqual() {
        ResourceType<TestRel, TestPerm> first = ResourceType.of("test", TestRel.class, TestPerm.class);
        ResourceType<TestRel, TestPerm> second = ResourceType.of("test", TestRel.class, TestPerm.class);
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void toStringReturnsName() {
        assertThat(ResourceType.of("test", TestRel.class, TestPerm.class).toString()).isEqualTo("test");
    }
}
