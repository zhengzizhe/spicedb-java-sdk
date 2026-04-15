package com.authx.clustertest.matrix;

import java.util.List;
import java.util.Map;

/**
 * Single benchmark matrix cell: one (workload × distribution × hit-rate × concurrency)
 * configuration with full latency percentile breakdown.
 */
public record MatrixCell(
        String name,
        String workload,            // "READ", "WRITE", "MIXED"
        String distribution,        // "uniform", "zipfian-1.5", "single-hot"
        double targetHitRate,       // 0.0..1.0
        int threads,
        long durationMs,
        long ops,
        double tps,
        double actualHitRate,
        long minUs, long p50us, long p90us, long p99us, long p999us, long p9999us, long maxUs,
        long errors,
        Map<String, Long> errorsByType,
        List<long[]> histogramBuckets   // [valueAtPercentile, percentile×100] for chart
) {}
