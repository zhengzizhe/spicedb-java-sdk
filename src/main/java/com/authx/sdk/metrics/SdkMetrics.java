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

    // ---- Latency overflow (SR:C9) ----
    // Count of recordRequest() samples that exceeded MAX_TRACKABLE_MICROS and
    // were clamped. Without this counter, extremely slow requests are silently
    // indistinguishable from requests that happened to clock at exactly the
    // clamp value — the tail of the percentile distribution is hidden. The
    // counter surfaces the event so operators can alert on it; the clamp
    // itself stays to bound HdrHistogram memory.
    private final LongAdder latencyOverflow = new LongAdder();

    // ---- Latency (HdrHistogram dual-buffer, microseconds) ----
    //
    // Two distinct histograms here, with carefully-separated roles:
    //
    //   * recycleBuffer — the buffer the Recorder fills on each drain. Owned
    //     exclusively by rotateHistogram(), never read by anything else. We
    //     recycle the same instance to avoid per-rotation allocation.
    //
    //   * publishedInterval — a read-only copy that snapshot() reads from.
    //     Refreshed by rotateHistogram() (every 5s by the scheduler) with a
    //     defensive .copy() of recycleBuffer so readers see a stable, immutable
    //     view. volatile because it's written on the scheduler thread and read
    //     on caller threads.
    //
    // Historical bug (F10-1): both rotateHistogram and getHistogram used to
    // call recorder.getIntervalHistogram(), and each call drains the recorder.
    // That meant snapshot() silently discarded whatever the scheduler had just
    // published. Callers that polled metrics right after a load test ran would
    // see p50=p99=0 because the published window had been drained by their
    // own snapshot() call before they could read it. Keeping the drain
    // exclusively on rotateHistogram() fixes this.
    // SR:C9 — raised from 60s to 600s (10 minutes). HdrHistogram memory cost
    // grows logarithmically in the trackable range at a fixed precision, so
    // moving the ceiling 10× adds only ~10KB while covering all realistic
    // request timeouts. Samples above this ceiling still clamp (bounding
    // memory) but also increment {@link #latencyOverflow} so they're visible.
    private static final long MAX_TRACKABLE_MICROS = 600_000_000L;
    private final Recorder recorder = new Recorder(MAX_TRACKABLE_MICROS, 3);
    private Histogram recycleBuffer;             // guarded by `this`
    private volatile Histogram publishedInterval;

    // ---- Cache stats source (from Cache implementation, replaces manual hit/miss counting) ----
    private volatile Supplier<CacheStats> cacheStatsSource;

    // ---- Circuit Breaker (injected via supplier) ----
    private volatile Supplier<String> circuitBreakerStateSupplier = () -> "N/A";

    // ---- Record methods (called by transport layers) ----

    // NOTE: these record methods are FALLBACK counters used only when no
    // external cacheStatsSource has been wired (e.g. in-memory clients or
    // tests). In normal production builds the AuthxClientBuilder calls
    // setCacheStatsSource(...) to forward to CaffeineCache.stats(), and
    // cacheHits() / cacheMisses() / cacheEvictions() then read from the
    // source — the increments below still fire but their values are ignored.
    // Kept public for backward-compat of the 1.0.x API.
    public void recordCacheHit() { cacheHits.increment(); }
    public void recordCacheMiss() { cacheMisses.increment(); }
    public void recordCacheEviction() { cacheEvictions.increment(); }
    public void updateCacheSize(long size) { cacheSize.set(size); }

    public void recordRequest(long latencyMicros, boolean error) {
        totalRequests.increment();
        if (error) totalErrors.increment();
        // SR:C9 — count clamps separately so operators can alert on them.
        // A sample above MAX_TRACKABLE_MICROS indicates either a request that
        // genuinely took > 10 minutes (stuck thread, network black hole) or a
        // misuse of recordRequest with a bogus latency unit; either way it
        // deserves visibility distinct from "everything was ≤ 10min".
        if (latencyMicros > MAX_TRACKABLE_MICROS) {
            latencyOverflow.increment();
            recorder.recordValue(MAX_TRACKABLE_MICROS);
        } else {
            recorder.recordValue(latencyMicros);
        }
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

    /**
     * Number of {@link #recordRequest} samples that exceeded the internal
     * latency clamp (≈ 10 minutes) and were therefore capped in the
     * percentile distribution. A non-zero value indicates requests that either
     * ran for an extremely long time (stuck thread, network black hole) or
     * were recorded with a bogus latency; alert on it to catch both cases.
     * See SR:C9.
     */
    public long latencyOverflowCount() { return latencyOverflow.sum(); }

    public double errorRate() {
        long total = totalRequests.sum();
        return total == 0 ? 0.0 : (double) totalErrors.sum() / total;
    }

    public String circuitBreakerState() {
        return circuitBreakerStateSupplier.get();
    }

    /** Full snapshot for logging or export. */
    public Snapshot snapshot() {
        Histogram h = publishedInterval;
        // Lazy first rotation: if no scheduled rotate has fired yet (common in
        // the first 5 seconds after startup, or in tests that don't wire a
        // scheduler), force one so the caller sees cumulative data instead of
        // a misleadingly-empty histogram. After this point rotateHistogram()
        // takes over on its 5s cadence.
        if (h == null) {
            synchronized (this) {
                if (publishedInterval == null) {
                    rotateHistogram();
                }
                h = publishedInterval;
            }
        }
        // O2 fix: distinguish "no data in this window" from "0ms latency". An
        // idle SDK between two rotations produces an empty histogram, and
        // HdrHistogram.getValueAtPercentile() returns 0 for empty — which is
        // indistinguishable from "everything was sub-microsecond fast".
        // Return Double.NaN so operators (and the toString() formatter below)
        // can tell the two cases apart.
        double p50, p95, p99, avg;
        if (h.getTotalCount() == 0) {
            p50 = p95 = p99 = avg = Double.NaN;
        } else {
            p50 = h.getValueAtPercentile(50.0) / 1000.0;
            p95 = h.getValueAtPercentile(95.0) / 1000.0;
            p99 = h.getValueAtPercentile(99.0) / 1000.0;
            avg = h.getMean() / 1000.0;
        }
        return new Snapshot(
                cacheHitRate(), cacheHits(), cacheMisses(), cacheEvictions(), cacheSize(),
                totalRequests(), totalErrors(), errorRate(), coalescedRequests(),
                p50, p95, p99, avg,
                circuitBreakerState(), watchReconnects(), latencyOverflowCount());
    }

    public record Snapshot(
            double cacheHitRate, long cacheHits, long cacheMisses, long cacheEvictions, long cacheSize,
            long totalRequests, long totalErrors, double errorRate, long coalescedRequests,
            double latencyP50Ms, double latencyP95Ms, double latencyP99Ms, double latencyAvgMs,
            String circuitBreakerState, long watchReconnects, long latencyOverflowCount
    ) {
        /** True iff the current rotation window contains no request samples. */
        public boolean latencyWindowEmpty() {
            return Double.isNaN(latencyP50Ms);
        }

        @Override
        public String toString() {
            String latencyStr = latencyWindowEmpty()
                    ? "latency=[no data in window]"
                    : String.format(
                            "latency=[p50=%.2fms p95=%.2fms p99=%.2fms avg=%.2fms]",
                            latencyP50Ms, latencyP95Ms, latencyP99Ms, latencyAvgMs);
            // SR:C9 — surface clamp count only when non-zero to avoid noise on
            // healthy deployments; a visible field signals "something unusual
            // happened in this window".
            String overflowStr = latencyOverflowCount > 0
                    ? String.format(", overflow=%d", latencyOverflowCount)
                    : "";
            return String.format(
                    "SdkMetrics{cache=%.1f%% (%d/%d), size=%d, evictions=%d, " +
                    "requests=%d, errors=%d (%.2f%%), coalesced=%d, " +
                    "%s, cb=%s, watchReconnects=%d%s}",
                    cacheHitRate * 100, cacheHits, cacheHits + cacheMisses, cacheSize, cacheEvictions,
                    totalRequests, totalErrors, errorRate * 100, coalescedRequests,
                    latencyStr, circuitBreakerState, watchReconnects, overflowStr);
        }
    }

    /**
     * Rotate the interval histogram. Called periodically by the scheduler
     * (every 5s by default) so that {@link #snapshot()} reads a stable,
     * fixed-window histogram instead of "everything since last read".
     *
     * <p>Drains the recorder into {@link #recycleBuffer} (reused to avoid
     * per-rotation allocation), then publishes a defensive copy so concurrent
     * readers can never observe a half-filled buffer.
     */
    public synchronized void rotateHistogram() {
        recycleBuffer = recorder.getIntervalHistogram(recycleBuffer);
        // copy() allocates ~per-rotation; at 5s cadence this is ~0.2 Hz which
        // is negligible. The alternative (handing out recycleBuffer directly)
        // would race against the next drain.
        publishedInterval = recycleBuffer.copy();
    }

}
