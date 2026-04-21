package com.authx.sdk.action;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation mirror for the batch path — {@link BatchBuilder#grant(String...)}
 * and {@link BatchBuilder#revoke(String...)} must reject mismatched subject
 * types at the point of {@code .to(...)} / {@code .from(...)} rather than
 * silently accumulating invalid updates that would only surface at
 * {@code execute()} time as a gRPC {@code INVALID_ARGUMENT}.
 */
class BatchActionsValidationTest {

    private SchemaCache schemaFor(String type, String relation, List<SubjectType> sts) {
        var c = new SchemaCache();
        c.updateFromMap(Map.of(type, new SchemaCache.DefinitionCache(
                Set.of(relation), Set.of(), Map.of(relation, sts))));
        return c;
    }

    @Test
    void batchGrant_rejectsWrongSubjectType() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        var batch = new BatchBuilder("document", "d-1", new InMemoryTransport(), cache);
        assertThatThrownBy(() -> batch.grant("folder").to("user:alice"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("[folder]");
    }

    @Test
    void batchGrant_acceptsAllowedSubjectType() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        var batch = new BatchBuilder("document", "d-1", new InMemoryTransport(), cache);
        batch.grant("folder").to("folder:f-1"); // no throw
        batch.execute();
    }

    @Test
    void batchRevoke_rejectsWrongSubjectType() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        var batch = new BatchBuilder("document", "d-1", new InMemoryTransport(), cache);
        assertThatThrownBy(() -> batch.revoke("folder").from("user:alice"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("[folder]");
    }

    @Test
    void batchRevoke_acceptsAllowedSubjectType() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        var batch = new BatchBuilder("document", "d-1", new InMemoryTransport(), cache);
        batch.revoke("folder").from("folder:f-1"); // no throw
        batch.execute();
    }

    @Test
    void nullCacheIsFailOpenOnBothSides() {
        var batch = new BatchBuilder("document", "d-1", new InMemoryTransport(), null);
        batch.grant("folder").to("user:alice");     // no throw
        batch.revoke("folder").from("user:alice");  // no throw
    }

    @Test
    void emptyCacheIsFailOpen() {
        var batch = new BatchBuilder("document", "d-1", new InMemoryTransport(), new SchemaCache());
        batch.grant("folder").to("user:alice");     // no throw
        batch.revoke("folder").from("user:alice");  // no throw
    }
}
