package com.authcses.sdk.metrics;

import com.authcses.sdk.circuit.CircuitBreaker;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * SDK internal metrics. Thread-safe, lock-free.
 *
 * <pre>
 * SdkMetrics m = client.metrics();
 * m.cacheHitRate();        // 0.85
 * m.checkLatencyP99();     // 5.2ms
 * m.circuitBreakerState(); // "CLOSED"
 * m.snapshot();            // full snapshot for logging/export
 * </pre>
 */
public class SdkMetrics {

    // ---- Cache ----
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheEvictions = new LongAdder();
    private final AtomicLong cacheSize = new AtomicLong(0);

    // ---- Requests ----
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder coalescedRequests = new LongAdder();

    // ---- Latency (rolling window, microseconds) ----
    private static final int LATENCY_WINDOW = 10_000;
    private final long[] latencyBuffer = new long[LATENCY_WINDOW];
    private final AtomicLong latencyIndex = new AtomicLong(0);

    // ---- Circuit Breaker (injected) ----
    private volatile CircuitBreaker circuitBreaker;

    // ---- Record methods (called by transport layers) ----

    public void recordCacheHit() { cacheHits.increment(); }
    public void recordCacheMiss() { cacheMisses.increment(); }
    public void recordCacheEviction() { cacheEvictions.increment(); }
    public void updateCacheSize(long size) { cacheSize.set(size); }

    public void recordRequest(long latencyMicros, boolean error) {
        totalRequests.increment();
        if (error) totalErrors.increment();

        int idx = (int) (latencyIndex.getAndIncrement() % LATENCY_WINDOW);
        latencyBuffer[idx] = latencyMicros;
    }

    public void recordCoalesced() { coalescedRequests.increment(); }

    public void setCircuitBreaker(CircuitBreaker cb) { this.circuitBreaker = cb; }

    // ---- Query methods (called by business code) ----

    /** Cache hit rate (0.0 to 1.0). Returns 0 if no cache activity. */
    public double cacheHitRate() {
        long hits = cacheHits.sum();
        long total = hits + cacheMisses.sum();
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public long cacheHits() { return cacheHits.sum(); }
    public long cacheMisses() { return cacheMisses.sum(); }
    public long cacheEvictions() { return cacheEvictions.sum(); }
    public long cacheSize() { return cacheSize.get(); }

    public long totalRequests() { return totalRequests.sum(); }
    public long totalErrors() { return totalErrors.sum(); }
    public long coalescedRequests() { return coalescedRequests.sum(); }

    public double errorRate() {
        long total = totalRequests.sum();
        return total == 0 ? 0.0 : (double) totalErrors.sum() / total;
    }

    /** Check latency p50 in milliseconds (from rolling window). */
    public double checkLatencyP50() { return percentile(0.50); }
    public double checkLatencyP95() { return percentile(0.95); }
    public double checkLatencyP99() { return percentile(0.99); }
    public double checkLatencyAvg() {
        long count = Math.min(latencyIndex.get(), LATENCY_WINDOW);
        if (count == 0) return 0;
        long sum = 0;
        for (int i = 0; i < count; i++) sum += latencyBuffer[i];
        return (sum / (double) count) / 1000.0; // micros → ms
    }

    public String circuitBreakerState() {
        return circuitBreaker != null ? circuitBreaker.getState().name() : "N/A";
    }

    /** Full snapshot for logging or export. */
    public Snapshot snapshot() {
        return new Snapshot(
                cacheHitRate(), cacheHits(), cacheMisses(), cacheEvictions(), cacheSize(),
                totalRequests(), totalErrors(), errorRate(), coalescedRequests(),
                checkLatencyP50(), checkLatencyP95(), checkLatencyP99(), checkLatencyAvg(),
                circuitBreakerState());
    }

    public record Snapshot(
            double cacheHitRate, long cacheHits, long cacheMisses, long cacheEvictions, long cacheSize,
            long totalRequests, long totalErrors, double errorRate, long coalescedRequests,
            double latencyP50Ms, double latencyP95Ms, double latencyP99Ms, double latencyAvgMs,
            String circuitBreakerState
    ) {
        @Override
        public String toString() {
            return String.format(
                    "SdkMetrics{cache=%.1f%% (%d/%d), size=%d, evictions=%d, " +
                    "requests=%d, errors=%d (%.2f%%), coalesced=%d, " +
                    "latency=[p50=%.2fms p95=%.2fms p99=%.2fms avg=%.2fms], cb=%s}",
                    cacheHitRate * 100, cacheHits, cacheHits + cacheMisses, cacheSize, cacheEvictions,
                    totalRequests, totalErrors, errorRate * 100, coalescedRequests,
                    latencyP50Ms, latencyP95Ms, latencyP99Ms, latencyAvgMs,
                    circuitBreakerState);
        }
    }

    private double percentile(double p) {
        long count = Math.min(latencyIndex.get(), LATENCY_WINDOW);
        if (count == 0) return 0;
        long[] sorted = Arrays.copyOf(latencyBuffer, (int) count);
        Arrays.sort(sorted);
        int idx = (int) (count * p);
        return sorted[Math.min(idx, sorted.length - 1)] / 1000.0; // micros → ms
    }
}
