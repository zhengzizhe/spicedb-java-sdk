package com.authcses.sdk.transport;

import com.authcses.sdk.cache.CheckCache;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WatchCacheInvalidatorTest {

    @Test
    void close_stopsWatchThread() {
        var invalidator = new WatchCacheInvalidator(
                io.grpc.ManagedChannelBuilder.forTarget("localhost:0").usePlaintext().build(),
                "test-key",
                CheckCache.noop());

        assertThat(invalidator.isRunning()).isTrue();
        invalidator.close();
        assertThat(invalidator.isRunning()).isFalse();
    }
}
