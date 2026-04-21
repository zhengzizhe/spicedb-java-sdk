package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.internal.SdkConfig;
import com.authx.sdk.internal.SdkInfrastructure;
import com.authx.sdk.internal.SdkObservability;
import com.authx.sdk.lifecycle.LifecycleManager;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.spi.HealthProbe;
import com.authx.sdk.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wiring smoke test — proves that a populated {@link SchemaCache} handed to
 * {@link AuthxClient} reaches {@code GrantAction.writeRelationships} via
 * {@code AuthxClient.on(type).resource(id).grant(rel).to(subject)}.
 *
 * <p>Unit-level behaviour of the validation itself lives in
 * {@code GrantActionValidationTest}; this test exercises the plumbing.
 */
class SchemaCacheWiringTest {

    /** Build an AuthxClient backed by InMemoryTransport with the given SchemaCache. */
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
    void untypedChainEnforcesValidationWhenCachePopulated() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        try (var c = client(cache)) {
            var factory = c.on("document");
            assertThatThrownBy(() -> factory.resource("d-1").grant("folder").to("user:alice"))
                    .isInstanceOf(InvalidRelationException.class)
                    .hasMessageContaining("[folder]");
            // Sanity — an allowed subject type passes the gate.
            factory.resource("d-1").grant("folder").to("folder:f-1");
        }
    }

    @Test
    void oneOffResourceHandleAlsoEnforcesValidation() {
        var cache = schemaFor("document", "folder", List.of(SubjectType.of("folder")));
        try (var c = client(cache)) {
            assertThatThrownBy(() -> c.resource("document", "d-1").grant("folder").to("user:alice"))
                    .isInstanceOf(InvalidRelationException.class)
                    .hasMessageContaining("[folder]");
        }
    }

    @Test
    void nullCacheIsFailOpen() {
        var bus = new DefaultTypedEventBus();
        var lm = new LifecycleManager(bus);
        lm.begin(); lm.complete();
        var infra = new SdkInfrastructure(null, null, Runnable::run, lm);
        var obs = new SdkObservability(new SdkMetrics(), bus, null);
        var cfg = new SdkConfig(PolicyRegistry.withDefaults(), false, false);
        try (var c = new AuthxClient(new InMemoryTransport(), infra, obs, cfg, HealthProbe.up())) {
            // No schema cache → any subject string accepted (fail-open).
            c.on("document").resource("d-1").grant("folder").to("user:alice");
        }
    }
}
