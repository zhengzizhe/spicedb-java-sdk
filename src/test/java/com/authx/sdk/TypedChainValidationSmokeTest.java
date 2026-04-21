package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.internal.SdkConfig;
import com.authx.sdk.internal.SdkInfrastructure;
import com.authx.sdk.internal.SdkObservability;
import com.authx.sdk.lifecycle.LifecycleManager;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.spi.HealthProbe;
import com.authx.sdk.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Typed-chain smoke test for req-9 — proves that the fluent typed path
 * ({@code client.on(TYPE).select(id).grant(Enum).to(...)} and the revoke
 * mirror) inherits the same schema-aware subject validation that
 * {@link com.authx.sdk.action.GrantAction} and
 * {@link com.authx.sdk.action.RevokeAction} perform at the untyped layer.
 *
 * <p>This is explicitly a smoke test: it doesn't exhaust every overload,
 * just asserts that the typed surface doesn't bypass validation. Unit
 * coverage of the validation itself lives in the untyped action tests.
 */
class TypedChainValidationSmokeTest {

    /** Minimal enum standing in for a codegen-emitted Rel enum. */
    enum TestRel implements Relation.Named {
        FOLDER("folder", SubjectType.of("folder"));

        private final String value;
        private final List<SubjectType> subjectTypes;

        TestRel(String value, SubjectType... sts) {
            this.value = value;
            this.subjectTypes = List.of(sts);
        }

        @Override public String relationName() { return value; }
        @Override public List<SubjectType> subjectTypes() { return subjectTypes; }
    }

    /** Matching Perm enum so ResourceType.of compiles. */
    enum TestPerm implements Permission.Named {
        VIEW("view");

        private final String value;
        TestPerm(String v) { this.value = v; }
        @Override public String permissionName() { return value; }
    }

    private static final ResourceType<TestRel, TestPerm> DOC_TYPE =
            ResourceType.of("document", TestRel.class, TestPerm.class);

    private AuthxClient client(SchemaCache cache) {
        var bus = new DefaultTypedEventBus();
        var lm = new LifecycleManager(bus);
        lm.begin(); lm.complete();
        var infra = new SdkInfrastructure(null, null, Runnable::run, lm);
        var obs = new SdkObservability(new SdkMetrics(), bus, null);
        var cfg = new SdkConfig(PolicyRegistry.withDefaults(), false, false);
        return new AuthxClient(
                new InMemoryTransport(), infra, obs, cfg, HealthProbe.up(),
                new SchemaClient(cache), cache);
    }

    private SchemaCache schemaFor(String type, String relation, List<SubjectType> sts) {
        var c = new SchemaCache();
        c.updateFromMap(Map.of(type, new SchemaCache.DefinitionCache(
                Set.of(relation), Set.of(), Map.of(relation, sts))));
        return c;
    }

    @Test
    void typedGrantRejectsWrongSubjectType() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        try (var c = client(cache)) {
            assertThatThrownBy(() -> c.on(DOC_TYPE).select("d-1").grant(TestRel.FOLDER).to("user:alice"))
                    .isInstanceOf(InvalidRelationException.class)
                    .hasMessageContaining("[folder]");
        }
    }

    @Test
    void typedGrantAcceptsAllowedSubjectType() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        try (var c = client(cache)) {
            // No throw — validation lets folder subjects through.
            c.on(DOC_TYPE).select("d-1").grant(TestRel.FOLDER).to("folder:f-1");
        }
    }

    @Test
    void typedRevokeRejectsWrongSubjectType() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        try (var c = client(cache)) {
            // Prime the store so there's something to revoke, with a matching subject.
            c.on(DOC_TYPE).select("d-1").grant(TestRel.FOLDER).to("folder:f-1");
            assertThatThrownBy(() -> c.on(DOC_TYPE).select("d-1").revoke(TestRel.FOLDER).from("user:alice"))
                    .isInstanceOf(InvalidRelationException.class)
                    .hasMessageContaining("[folder]");
        }
    }
}
