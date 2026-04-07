package com.authx.sdk.transport;

import com.authx.sdk.cache.Cache;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WatchCacheInvalidatorTest {

    @Test
    void close_stopsWatchThread() {
        Cache<CheckKey, CheckResult> noop = Cache.noop();
        var invalidator = new WatchCacheInvalidator(
                io.grpc.ManagedChannelBuilder.forTarget("localhost:0").usePlaintext().build(),
                "test-key",
                noop,
                new SdkMetrics());
        invalidator.start();

        assertThat(invalidator.isRunning()).isTrue();
        invalidator.close();
        assertThat(invalidator.isRunning()).isFalse();
    }
}
