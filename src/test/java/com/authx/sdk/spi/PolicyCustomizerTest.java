package com.authx.sdk.spi;

import com.authx.sdk.policy.CachePolicy;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.policy.ResourcePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyCustomizerTest {

    @Test
    void singleCustomizer_setsDefaultPolicyFields() {
        PolicyCustomizer c = policies -> policies.defaultPolicy(
                ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(7)))
                        .readConsistency(ReadConsistency.session())
                        .build());

        var builder = PolicyRegistry.builder();
        c.customize(builder);
        var registry = builder.build();

        var resolved = registry.resolve("document");
        assertThat(resolved.cache().ttl()).isEqualTo(Duration.ofSeconds(7));
        assertThat(resolved.readConsistency()).isEqualTo(ReadConsistency.session());
    }

    @Test
    void composedCustomizers_areAppliedInOrder_laterWinsOnDefaultPolicy() {
        // First customizer sets a "sane defaults" baseline.
        PolicyCustomizer baseline = policies -> policies.defaultPolicy(
                ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(5)))
                        .build());

        // Second customizer overrides — in the same builder, the LAST
        // defaultPolicy() call wins because each call replaces the
        // previous value.
        PolicyCustomizer override = policies -> policies.defaultPolicy(
                ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(30)))
                        .build());

        var builder = PolicyRegistry.builder();
        baseline.customize(builder);
        override.customize(builder);
        var registry = builder.build();

        assertThat(registry.resolve("anything").cache().ttl())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void customizerComposition_defaultPlusPerResource() {
        PolicyCustomizer def = policies -> policies.defaultPolicy(
                ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(10)))
                        .build());

        PolicyCustomizer invoiceOverride = policies -> policies.forResourceType("invoice",
                ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(1)))
                        .build());

        var builder = PolicyRegistry.builder();
        def.customize(builder);
        invoiceOverride.customize(builder);
        var registry = builder.build();

        assertThat(registry.resolve("document").cache().ttl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(registry.resolve("invoice").cache().ttl()).isEqualTo(Duration.ofSeconds(1));
    }
}
