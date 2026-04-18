package com.authx.clustertest.benchmark;

import java.util.Map;

public record BenchmarkResult(
        String scenario, int threads, long durationMs, long ops, double tps,
        long p50us, long p90us, long p99us, long p999us, long maxUs,
        long errors, Map<String, Long> errorsByType
) {}
