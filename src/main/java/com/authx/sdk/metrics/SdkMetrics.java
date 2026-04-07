package com.authx.sdk.metrics;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import com.authx.sdk.cache.CacheStats;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

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

    // ---- Watch ----
    private final LongAdder watchReconnects = new LongAdder();

    // ---- Latency (HdrHistogram dual-buffer, microseconds) ----
    private static final long MAX_TRACKABLE_MICROS = 60_000_000L;
    private final Recorder recorder = new Recorder(MAX_TRACKABLE_MICROS, 3);
    private volatile Histogram intervalHistogram;

    // ---- Cache stats source (from Cache implementation, replaces manual hit/miss counting) ----
    private volatile Supplier<CacheStats> cacheStatsSource;

    // ---- Circuit Breaker (injected via supplier) ----
    private volatile Supplier<String> circuitBreakerStateSupplier = () -> "N/A";

    // ---- Record methods (called by transport layers) ----

    public void recordCacheHit() { cacheHits.increment(); }
    public void recordCacheMiss() { cacheMisses.increment(); }
    public void recordCacheEviction() { cacheEvictions.increment(); }
    public void updateCacheSize(long size) { cacheSize.set(size); }

    public void recordRequest(long latencyMicros, boolean error) {
        totalRequests.increment();
        if (error) totalErrors.increment();
        recorder.recordValue(Math.min(latencyMicros, MAX_TRACKABLE_MICROS));
    }

    public void recordCoalesced() { coalescedRequests.increment(); }
    public void recordWatchReconnect() { watchReconnects.increment(); }

    public void setCacheStatsSource(Supplier<CacheStats> source) {
        this.cacheStatsSource = source;
    }

    public void setCircuitBreakerStateSupplier(Supplier<String> supplier) {
        this.circuitBreakerStateSupplier = supplier;
    }

    // ---- Query methods (called by business code) ----

    /** Cache hit rate (0.0 to 1.0). Returns 0 if no cache activity. */
    public double cacheHitRate() {
        long hits = cacheHits();
        long total = hits + cacheMisses();
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public long cacheHits() {
        var source = cacheStatsSource;
        return source != null ? source.get().hitCount() : cacheHits.sum();
    }

    public long cacheMisses() {
        var source = cacheStatsSource;
        return source != null ? source.get().missCount() : cacheMisses.sum();
    }

    public long cacheEvictions() {
        var source = cacheStatsSource;
        return source != null ? source.get().evictionCount() : cacheEvictions.sum();
    }
    public long cacheSize() { return cacheSize.get(); }

    public long totalRequests() { return totalRequests.sum(); }
    public long totalErrors() { return totalErrors.sum(); }
    public long coalescedRequests() { return coalescedRequests.sum(); }
    public long watchReconnects() { return watchReconnects.sum(); }

    public double errorRate() {
        long total = totalRequests.sum();
        return total == 0 ? 0.0 : (double) totalErrors.sum() / total;
    }

    /**
     * @deprecated Use {@link #snapshot()} to read all percentiles in one call.
     *             Each individual call acquires a lock and resets the interval histogram.
     */
    @Deprecated(since = "1.0.0")
    public double checkLatencyP50() { return snapshot().latencyP50Ms(); }

    /**
     * @deprecated Use {@link #snapshot()} to read all percentiles in one call.
     */
    @Deprecated(since = "1.0.0")
    public double checkLatencyP95() { return snapshot().latencyP95Ms(); }

    /**
     * @deprecated Use {@link #snapshot()} to read all percentiles in one call.
     */
    @Deprecated(since = "1.0.0")
    public double checkLatencyP99() { return snapshot().latencyP99Ms(); }

    /**
     * @deprecated Use {@link #snapshot()} to read all metrics in one call.
     */
    @Deprecated(since = "1.0.0")
    public double checkLatencyAvg() { return snapshot().latencyAvgMs(); }

    public String circuitBreakerState() {
        return circuitBreakerStateSupplier.get();
    }

    /** Full snapshot for logging or export. */
    public Snapshot snapshot() {
        Histogram h = getHistogram();
        double p50 = h.getValueAtPercentile(50.0) / 1000.0;
        double p95 = h.getValueAtPercentile(95.0) / 1000.0;
        double p99 = h.getValueAtPercentile(99.0) / 1000.0;
        double avg = h.getMean() / 1000.0;
        return new Snapshot(
                cacheHitRate(), cacheHits(), cacheMisses(), cacheEvictions(), cacheSize(),
                totalRequests(), totalErrors(), errorRate(), coalescedRequests(),
                p50, p95, p99, avg,
                circuitBreakerState(), watchReconnects());
    }

    public record Snapshot(
            double cacheHitRate, long cacheHits, long cacheMisses, long cacheEvictions, long cacheSize,
            long totalRequests, long totalErrors, double errorRate, long coalescedRequests,
            double latencyP50Ms, double latencyP95Ms, double latencyP99Ms, double latencyAvgMs,
            String circuitBreakerState, long watchReconnects
    ) {
        @Override
        public String toString() {
            return String.format(
                    "SdkMetrics{cache=%.1f%% (%d/%d), size=%d, evictions=%d, " +
                    "requests=%d, errors=%d (%.2f%%), coalesced=%d, " +
                    "latency=[p50=%.2fms p95=%.2fms p99=%.2fms avg=%.2fms], cb=%s, watchReconnects=%d}",
                    cacheHitRate * 100, cacheHits, cacheHits + cacheMisses, cacheSize, cacheEvictions,
                    totalRequests, totalErrors, errorRate * 100, coalescedRequests,
                    latencyP50Ms, latencyP95Ms, latencyP99Ms, latencyAvgMs,
                    circuitBreakerState, watchReconnects);
        }
    }

    /**
     * Rotate the interval histogram. Called periodically by the scheduler (e.g. every 5s)
     * so that snapshot() reads a stable, fixed-window histogram instead of
     * "everything since last read".
     */
    public synchronized void rotateHistogram() {
        intervalHistogram = recorder.getIntervalHistogram(intervalHistogram);
    }

    /**
     * Retrieves the current interval histogram from the Recorder.
     * Must be synchronized because Recorder.getIntervalHistogram is not safe
     * for concurrent callers on the same intervalHistogram instance.
     */
    private synchronized Histogram getHistogram() {
        intervalHistogram = recorder.getIntervalHistogram(intervalHistogram);
        return intervalHistogram;
    }

}
