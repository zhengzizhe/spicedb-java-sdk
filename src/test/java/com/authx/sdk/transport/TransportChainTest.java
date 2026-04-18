package com.authx.sdk.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for the 2026-04-18 L1 cache + Watch removal:
 * the transport layers that were deleted must not return via new code,
 * and CoalescingTransport (deliberately preserved) must still exist.
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
    void schemaLoaderClassIsGone() {
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.transport.SchemaLoader"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void coalescingTransportClassIsPreserved() throws ClassNotFoundException {
        // Sanity check: we removed cache/Watch but kept in-flight dedup.
        Class<?> clazz = Class.forName("com.authx.sdk.transport.CoalescingTransport");
        assertThat(clazz).isNotNull();
    }

    @Test
    void cachePackageIsGone() {
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.cache.Cache"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.cache.CaffeineCache"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.authx.sdk.cache.SchemaCache"))
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
