package com.authx.clustertest.soak;

import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * Periodic snapshot of runtime resource usage during soak tests:
 * heap, thread count. Cache-related fields (cacheSize, hitRate,
 * watchReconnects) are reported as constants since the SDK removed
 * client-side decision caching (ADR 2026-04-18).
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

        // Cache is gone — report zeros to keep the sample record shape
        // stable for downstream HTML/JSON report consumers.
        return new Sample(ts, heapMB, threads, 0L, 0.0, 0L);
    }
}
