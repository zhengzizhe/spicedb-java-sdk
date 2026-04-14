package com.authx.clustertest.soak;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.cache.CacheStats;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * Periodic snapshot of runtime resource usage during soak tests:
 * heap, thread count, cache size, cache hit rate, watch reconnects.
 */
@Component
public class ResourceSampler {

    public record Sample(long tsSec,
                         long heapMB,
                         int threads,
                         long cacheSize,
                         double hitRate,
                         long watchReconnects) {}

    public Sample sample(AuthxClient client, long startSec) {
        long ts = (System.currentTimeMillis() / 1000) - startSec;
        Runtime rt = Runtime.getRuntime();
        long heapMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();

        long cacheSize = 0;
        double hitRate = 0.0;
        if (client.cache() != null) {
            cacheSize = client.cache().size();
            CacheStats stats = client.cache().stats();
            if (stats != null && stats.requestCount() > 0) {
                hitRate = (double) stats.hitCount() / stats.requestCount();
            }
        }

        // best-effort: watch reconnect metrics would require reaching into SdkMetrics;
        // left as 0 until/unless exposed via a public counter.
        long watchReconnects = 0L;

        return new Sample(ts, heapMB, threads, cacheSize, hitRate, watchReconnects);
    }
}
