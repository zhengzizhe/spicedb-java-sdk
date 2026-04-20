package com.authx.sdk.trace;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class LogFieldsTest {

    @Test
    void suffix_allFieldsNull_returnsEmpty() {
        assertThat(LogFields.suffix(null, null, null, null)).isEqualTo("");
        assertThat(LogFields.suffixPerm(null, null, null, null)).isEqualTo("");
        assertThat(LogFields.suffixRel(null, null, null, null)).isEqualTo("");
    }

    @Test
    void suffix_typeAndResOnly() {
        assertThat(LogFields.suffix("document", "doc-1", null, null))
                .isEqualTo(" [type=document res=doc-1]");
    }

    @Test
    void suffixPerm_allFields() {
        assertThat(LogFields.suffixPerm("document", "doc-1", "view", "user:alice"))
                .isEqualTo(" [type=document res=doc-1 perm=view subj=user:alice]");
    }

    @Test
    void suffixRel_allFields() {
        assertThat(LogFields.suffixRel("document", "doc-1", "editor", "user:bob"))
                .isEqualTo(" [type=document res=doc-1 rel=editor subj=user:bob]");
    }

    @Test
    void suffix_emptyStringsTreatedAsBlank() {
        assertThat(LogFields.suffix("", "", "", "")).isEqualTo("");
    }

    @Test
    void suffix_partialFields_onlyPresentEmitted() {
        assertThat(LogFields.suffixPerm("document", null, "view", null))
                .isEqualTo(" [type=document perm=view]");
    }

    @Test
    void toMdcMap_skipsNullAndBlank() {
        var map = LogFields.toMdcMap("CHECK", "document", "doc-1",
                "view", null, "user:alice", "minimize_latency");
        assertThat(map)
                .containsEntry(LogFields.KEY_ACTION, "CHECK")
                .containsEntry(LogFields.KEY_RESOURCE_TYPE, "document")
                .containsEntry(LogFields.KEY_PERMISSION, "view")
                .doesNotContainKey(LogFields.KEY_RELATION);
    }

    @Test
    void toMdcMap_allNull_returnsEmptyMap() {
        var map = LogFields.toMdcMap(null, null, null, null, null, null, null);
        assertThat(map).isEmpty();
    }

    @Test
    void allKeyConstants_prefixedAuthxDot_and15Count() throws IllegalAccessException {
        int keyCount = 0;
        for (Field f : LogFields.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!f.getName().startsWith("KEY_")) continue;
            Object v = f.get(null);
            assertThat(v).isInstanceOf(String.class);
            assertThat((String) v).startsWith("authx.");
            keyCount++;
        }
        assertThat(keyCount).as("15 MDC key constants expected").isEqualTo(15);
    }
}
