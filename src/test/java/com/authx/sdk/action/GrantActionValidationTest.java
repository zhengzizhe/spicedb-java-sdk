package com.authx.sdk.action;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrantActionValidationTest {

    private SchemaCache schemaFor(String type, String relation, List<SubjectType> sts) {
        com.authx.sdk.cache.SchemaCache c = new SchemaCache();
        c.updateFromMap(Map.of(type, new SchemaCache.DefinitionCache(
                Set.of(relation), Set.of(), Map.of(relation, sts))));
        return c;
    }

    private SdkTransport recordingTransport(AtomicInteger calls) {
        return new InMemoryTransport() {
            @Override
            public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
                calls.incrementAndGet();
                return super.writeRelationships(updates);
            }
        };
    }

    @Test
    void rejectsWrongSubjectType() {
        com.authx.sdk.cache.SchemaCache cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        java.util.concurrent.atomic.AtomicInteger calls = new AtomicInteger();
        com.authx.sdk.action.GrantAction action = new GrantAction("document", "d-1", recordingTransport(calls),
                new String[]{"folder"}, cache);
        assertThatThrownBy(() -> action.to("user:alice"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("[folder]");
        assertThat(calls).hasValue(0);
    }

    @Test
    void acceptsAllowedSubjectType() {
        com.authx.sdk.cache.SchemaCache cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        java.util.concurrent.atomic.AtomicInteger calls = new AtomicInteger();
        com.authx.sdk.action.GrantAction action = new GrantAction("document", "d-1", recordingTransport(calls),
                new String[]{"folder"}, cache);
        action.to("folder:f-1");
        assertThat(calls).hasValue(1);
    }

    @Test
    void nullSchemaCacheIsFailOpen() {
        java.util.concurrent.atomic.AtomicInteger calls = new AtomicInteger();
        com.authx.sdk.action.GrantAction action = new GrantAction("document", "d-1", recordingTransport(calls),
                new String[]{"folder"}, null);
        action.to("user:alice"); // no throw
        assertThat(calls).hasValue(1);
    }

    @Test
    void emptySchemaCacheIsFailOpen() {
        java.util.concurrent.atomic.AtomicInteger calls = new AtomicInteger();
        com.authx.sdk.action.GrantAction action = new GrantAction("document", "d-1", recordingTransport(calls),
                new String[]{"folder"}, new SchemaCache());
        action.to("user:alice"); // no throw
        assertThat(calls).hasValue(1);
    }
}
