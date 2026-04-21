package com.authx.sdk.action;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.model.RevokeResult;
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

/**
 * Mirror of {@code GrantActionValidationTest} — proves that
 * {@link RevokeAction} consumes the same schema-aware validation path
 * and aborts the RPC before calling the transport when the subject type
 * doesn't match what the schema declares.
 *
 * <p>Fail-open semantics (null cache / empty cache) must behave the same
 * as {@link GrantAction} so callers who disable schema loading see no
 * behaviour change on the revoke side either.
 */
class RevokeActionValidationTest {

    private SchemaCache schemaFor(String type, String relation, List<SubjectType> sts) {
        var c = new SchemaCache();
        c.updateFromMap(Map.of(type, new SchemaCache.DefinitionCache(
                Set.of(relation), Set.of(), Map.of(relation, sts))));
        return c;
    }

    private SdkTransport recordingTransport(AtomicInteger calls) {
        return new InMemoryTransport() {
            @Override
            public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
                calls.incrementAndGet();
                return super.deleteRelationships(updates);
            }
        };
    }

    @Test
    void rejectsWrongSubjectType() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        var calls = new AtomicInteger();
        var action = new RevokeAction("document", "d-1", recordingTransport(calls),
                new String[]{"folder"}, cache);
        assertThatThrownBy(() -> action.from("user:alice"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("[folder]");
        assertThat(calls).hasValue(0);
    }

    @Test
    void acceptsAllowedSubjectType() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        var calls = new AtomicInteger();
        var action = new RevokeAction("document", "d-1", recordingTransport(calls),
                new String[]{"folder"}, cache);
        action.from("folder:f-1");
        assertThat(calls).hasValue(1);
    }

    @Test
    void nullSchemaCacheIsFailOpen() {
        var calls = new AtomicInteger();
        var action = new RevokeAction("document", "d-1", recordingTransport(calls),
                new String[]{"folder"}, null);
        action.from("user:alice"); // no throw
        assertThat(calls).hasValue(1);
    }

    @Test
    void emptySchemaCacheIsFailOpen() {
        var calls = new AtomicInteger();
        var action = new RevokeAction("document", "d-1", recordingTransport(calls),
                new String[]{"folder"}, new SchemaCache());
        action.from("user:alice"); // no throw
        assertThat(calls).hasValue(1);
    }
}
