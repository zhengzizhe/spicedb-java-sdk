package com.authx.sdk.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for the 2026-04-18 L1 <b>decision</b> cache + Watch
 * removal (ADR 2026-04-18): the transport layers and decision-cache
 * types that were deleted must not return, and {@link CoalescingTransport}
 * (deliberately preserved) must still exist.
 *
 * <p>Note: the schema-metadata cache ({@code com.authx.sdk.cache.SchemaCache})
 * and its loader ({@code com.authx.sdk.transport.SchemaLoader}) live in
 * the same package names as the old decision cache but are unrelated —
 * they only carry schema metadata (relations / permissions / subject types)
 * pulled from {@code ExperimentalService.ReflectSchema} and do not cache
 * any permission decisions. See the 2026-04-21 schema-aware codegen work
 * for why they were reintroduced.
 */
class TransportChainTest {

    @Test
    void cachedTransportClassIsGone() {
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.transport.CachedTransport"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void watchCacheInvalidatorClassIsGone() {
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.transport.WatchCacheInvalidator"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void coalescingTransportClassIsPreserved() throws ClassNotFoundException {
        // Sanity check: we removed cache/Watch but kept in-flight dedup.
        Class<?> clazz = Class.forName("com.authx.sdk.transport.CoalescingTransport");
        assertThat(clazz).isNotNull();
    }

    @Test
    void decisionCacheClassesAreGone() {
        // The decision-cache types (Cache<K,V>, Caffeine-backed impl) must
        // stay gone — SpiceDB's server-side dispatch cache owns that layer.
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.cache.Cache"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.cache.CaffeineCache"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void watchPackageIsGone() {
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.watch.WatchDispatcher"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.watch.WatchStrategy"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
